"""向量存储：Milvus 优先，本地 numpy + JSON 兜底。

本地存储位于 ``LOCAL_VECTOR_DIR``，启动时加载；写入采用「临时文件 + 原子替换」。
向量按 ``chunk_id`` upsert，按 ``parse_record_id`` 清理旧版本，检索支持
``user_id + document_id + parse_record_id`` 过滤。
"""

from __future__ import annotations

import json
import logging
import os
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np

from ..config import Settings, get_settings

logger = logging.getLogger(__name__)

try:  # pymilvus 缺失时仍然可以只用本地向量库
    from pymilvus import DataType, MilvusClient

    _MILVUS_IMPORT_ERROR: Exception | None = None
except Exception as exc:  # noqa: BLE001
    DataType = None  # type: ignore[assignment]
    MilvusClient = None  # type: ignore[assignment]
    _MILVUS_IMPORT_ERROR = exc


@dataclass(slots=True)
class VectorItem:
    chunk_id: str
    user_id: int
    document_id: int
    parse_record_id: int
    section: str
    page_start: int | None
    page_end: int | None
    content: str
    embedding_model: str
    vector: list[float] = field(repr=False)


def _to_meta(item: VectorItem) -> dict[str, Any]:
    return {
        "chunkId": item.chunk_id,
        "userId": int(item.user_id or 0),
        "documentId": int(item.document_id or 0),
        "parseRecordId": int(item.parse_record_id or 0),
        "section": item.section or "",
        "pageStart": item.page_start,
        "pageEnd": item.page_end,
        "content": item.content or "",
        "embeddingModel": item.embedding_model or "",
    }


class LocalVectorStore:
    """numpy 矩阵 + JSON metadata 的轻量向量库。"""

    VECTORS_FILE = "vectors.npy"
    META_FILE = "metadata.json"

    def __init__(self, directory: Path, dim: int) -> None:
        self.directory = Path(directory)
        self.dim = dim
        self._lock = threading.RLock()
        self._vectors = np.zeros((0, dim), dtype=np.float32)
        self._meta: list[dict[str, Any]] = []
        self._user_ids = np.zeros((0,), dtype=np.int64)
        self._doc_ids = np.zeros((0,), dtype=np.int64)
        self._parse_ids = np.zeros((0,), dtype=np.int64)
        self._load()

    # ------------------------------------------------------------------ 加载
    @property
    def vectors_path(self) -> Path:
        return self.directory / self.VECTORS_FILE

    @property
    def meta_path(self) -> Path:
        return self.directory / self.META_FILE

    def _load(self) -> None:
        try:
            self.directory.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            logger.warning("无法创建本地向量目录 %s：%s", self.directory, exc)
            return

        if not self.vectors_path.exists() or not self.meta_path.exists():
            logger.info("本地向量库为空（%s）", self.directory)
            return

        try:
            vectors = np.load(self.vectors_path, allow_pickle=False)
            with self.meta_path.open("r", encoding="utf-8") as handle:
                meta = json.load(handle)
            if not isinstance(meta, list):
                raise ValueError("metadata.json 不是列表")

            if vectors.ndim != 2 or vectors.shape[1] != self.dim:
                raise ValueError(
                    f"向量维度不匹配：文件 {vectors.shape}，配置 dim={self.dim}"
                )
            if vectors.shape[0] != len(meta):
                raise ValueError(f"向量行数 {vectors.shape[0]} 与 metadata {len(meta)} 不一致")

            self._vectors = vectors.astype(np.float32, copy=False)
            self._meta = meta
            self._refresh_arrays()
            logger.info("已加载本地向量库：%d 条，dim=%d，%s", len(meta), self.dim, self.directory)
        except Exception as exc:  # noqa: BLE001
            logger.warning("本地向量库加载失败，按空库启动：%s", exc)
            self._vectors = np.zeros((0, self.dim), dtype=np.float32)
            self._meta = []
            self._refresh_arrays()

    def _refresh_arrays(self) -> None:
        self._user_ids = np.asarray(
            [int(item.get("userId") or 0) for item in self._meta], dtype=np.int64
        )
        self._doc_ids = np.asarray(
            [int(item.get("documentId") or 0) for item in self._meta], dtype=np.int64
        )
        self._parse_ids = np.asarray(
            [int(item.get("parseRecordId") or 0) for item in self._meta], dtype=np.int64
        )

    # ------------------------------------------------------------------ 持久化
    def _persist(self) -> None:
        self.directory.mkdir(parents=True, exist_ok=True)
        vectors_tmp = self.vectors_path.with_name(self.vectors_path.name + ".tmp")
        meta_tmp = self.meta_path.with_name(self.meta_path.name + ".tmp")

        with vectors_tmp.open("wb") as handle:
            np.save(handle, self._vectors)
        with meta_tmp.open("w", encoding="utf-8") as handle:
            json.dump(self._meta, handle, ensure_ascii=False, indent=1)

        os.replace(vectors_tmp, self.vectors_path)
        os.replace(meta_tmp, self.meta_path)

    # ------------------------------------------------------------------ 写操作
    def delete_documents(self, document_ids: list[int]) -> int:
        targets = {int(value) for value in document_ids}
        if not targets:
            return 0

        with self._lock:
            keep = [index for index, item in enumerate(self._meta) if int(item["documentId"]) not in targets]
            removed = len(self._meta) - len(keep)
            if removed:
                self._vectors = self._vectors[keep] if keep else np.zeros((0, self.dim), dtype=np.float32)
                self._meta = [self._meta[index] for index in keep]
                self._refresh_arrays()
                self._persist()
            return removed

    def delete_parse_records(self, document_id: int, keep_parse_record_id: int | None = None) -> int:
        """删除某文献除 keep 版本外的旧向量。"""
        target_doc = int(document_id)

        with self._lock:
            keep = [
                index
                for index, item in enumerate(self._meta)
                if int(item["documentId"]) != target_doc
                or (
                    keep_parse_record_id is not None
                    and int(item["parseRecordId"]) == int(keep_parse_record_id)
                )
            ]
            removed = len(self._meta) - len(keep)
            if removed:
                self._vectors = self._vectors[keep] if keep else np.zeros((0, self.dim), dtype=np.float32)
                self._meta = [self._meta[index] for index in keep]
                self._refresh_arrays()
                self._persist()
            return removed

    def upsert(self, items: list[VectorItem]) -> int:
        if not items:
            return 0

        # 同一个 chunk_id 后写覆盖先写
        deduped: dict[str, VectorItem] = {}
        for item in items:
            deduped[item.chunk_id] = item
        items = list(deduped.values())
        new_ids = set(deduped)

        with self._lock:
            keep = [index for index, item in enumerate(self._meta) if item["chunkId"] not in new_ids]
            vectors = self._vectors[keep] if keep else np.zeros((0, self.dim), dtype=np.float32)
            meta = [self._meta[index] for index in keep]

            new_vectors = np.asarray([item.vector for item in items], dtype=np.float32)
            if new_vectors.shape[1] != self.dim:
                raise ValueError(
                    f"写入向量维度 {new_vectors.shape[1]} 与本地向量库维度 {self.dim} 不一致"
                )

            self._vectors = np.vstack([vectors, new_vectors]) if vectors.size else new_vectors
            self._meta = meta + [_to_meta(item) for item in items]
            self._refresh_arrays()
            self._persist()
            return len(items)

    # ------------------------------------------------------------------ 查询
    def search(
        self,
        query_vector: list[float],
        user_id: int | None,
        document_ids: list[int],
        parse_record_ids: list[int] | None,
        top_k: int,
    ) -> list[dict[str, Any]]:
        with self._lock:
            if not self._meta or self._vectors.shape[0] == 0:
                return []

            mask = np.ones(self._vectors.shape[0], dtype=bool)
            if user_id is not None:
                mask &= self._user_ids == int(user_id)
            if document_ids:
                mask &= np.isin(self._doc_ids, np.asarray(document_ids, dtype=np.int64))
            if parse_record_ids:
                mask &= np.isin(
                    self._parse_ids, np.asarray(parse_record_ids, dtype=np.int64)
                )

            indexes = np.where(mask)[0]
            if indexes.size == 0:
                return []

            query = np.asarray(query_vector, dtype=np.float32)
            if query.shape[0] != self.dim:
                raise ValueError(f"查询向量维度 {query.shape[0]} 与向量库维度 {self.dim} 不一致")
            norm = float(np.linalg.norm(query))
            if norm > 0:
                query = query / norm

            scores = self._vectors[indexes] @ query
            limit = max(1, min(int(top_k), indexes.size))
            order = np.argsort(-scores)[:limit]

            results: list[dict[str, Any]] = []
            for position in order:
                meta = dict(self._meta[int(indexes[int(position)])])
                meta["score"] = float(scores[int(position)])
                results.append(meta)
            return results

    def count(self) -> int:
        with self._lock:
            return len(self._meta)


class MilvusVectorStore:
    """基于 pymilvus MilvusClient 的向量库。"""

    def __init__(self, settings: Settings) -> None:
        if MilvusClient is None or DataType is None:
            raise RuntimeError(f"pymilvus 不可用：{_MILVUS_IMPORT_ERROR}")
        if not settings.milvus_uri.strip():
            raise RuntimeError("MILVUS_URI 为空")

        self.settings = settings
        self.dim = settings.embedding_dim
        self.collection = settings.milvus_collection
        self.timeout = settings.milvus_timeout_seconds
        self.client = MilvusClient(uri=settings.milvus_uri, timeout=self.timeout)

        if self.client.has_collection(self.collection, timeout=self.timeout):
            self._check_dimension()
            try:
                self.client.load_collection(self.collection, timeout=self.timeout)
            except Exception as exc:  # noqa: BLE001
                logger.debug("load_collection 忽略异常：%s", exc)
        else:
            self._create_collection()

        logger.info(
            "Milvus 已连接：uri=%s, collection=%s, dim=%d",
            settings.milvus_uri,
            self.collection,
            self.dim,
        )

    def _create_collection(self) -> None:
        schema = MilvusClient.create_schema(auto_id=False, enable_dynamic_field=False)
        schema.add_field("chunk_id", DataType.VARCHAR, is_primary=True, max_length=64)
        schema.add_field("user_id", DataType.INT64)
        schema.add_field("document_id", DataType.INT64)
        schema.add_field("parse_record_id", DataType.INT64)
        schema.add_field("section", DataType.VARCHAR, max_length=512)
        schema.add_field("page_start", DataType.INT32)
        schema.add_field("page_end", DataType.INT32)
        schema.add_field("content", DataType.VARCHAR, max_length=65535)
        schema.add_field("embedding_model", DataType.VARCHAR, max_length=100)
        schema.add_field("vector", DataType.FLOAT_VECTOR, dim=self.dim)

        index_params = self.client.prepare_index_params()
        index_params.add_index(field_name="vector", index_type="FLAT", metric_type="COSINE")
        self.client.create_collection(
            collection_name=self.collection,
            schema=schema,
            index_params=index_params,
            timeout=self.timeout,
        )
        logger.info("已创建 Milvus 集合 %s（dim=%d, COSINE）", self.collection, self.dim)

    def _check_dimension(self) -> None:
        info = self.client.describe_collection(self.collection, timeout=self.timeout)
        fields = info.get("fields") or []
        actual = None
        for item in fields:
            if item.get("name") == "vector":
                actual = (item.get("params") or {}).get("dim")
                break
        if actual is not None and int(actual) != self.dim:
            raise RuntimeError(
                f"Milvus 集合 {self.collection} 的向量维度是 {actual}，"
                f"与 EMBEDDING_DIM={self.dim} 不一致"
            )

    def close(self) -> None:
        try:
            self.client.close()
        except Exception:  # noqa: BLE001
            pass

    def delete_parse_records(self, document_id: int, keep_parse_record_id: int | None) -> int:
        """删除某文献除 keep 版本外的旧向量。"""
        expression = f"document_id == {int(document_id)}"
        if keep_parse_record_id is not None:
            expression += f" and parse_record_id != {int(keep_parse_record_id)}"
        result = self.client.delete(
            collection_name=self.collection, filter=expression, timeout=self.timeout
        )
        deleted = result.get("delete_count") if isinstance(result, dict) else None
        return int(deleted or 0)

    def delete_documents(self, document_ids: list[int]) -> int:
        targets = sorted({int(value) for value in document_ids})
        if not targets:
            return 0
        expression = "document_id in [" + ",".join(str(value) for value in targets) + "]"
        result = self.client.delete(
            collection_name=self.collection, filter=expression, timeout=self.timeout
        )
        deleted = result.get("delete_count") if isinstance(result, dict) else None
        return int(deleted or 0)

    def upsert(self, items: list[VectorItem]) -> int:
        if not items:
            return 0
        rows = []
        for item in items:
            if len(item.chunk_id) > 64:
                raise ValueError(f"chunk_id 超过 64 字符：{item.chunk_id}")
            rows.append(
                {
                    "chunk_id": item.chunk_id,
                    "user_id": int(item.user_id or 0),
                    "document_id": int(item.document_id or 0),
                    "parse_record_id": int(item.parse_record_id or 0),
                    "section": (item.section or "")[:512],
                    "page_start": int(item.page_start or 0),
                    "page_end": int(item.page_end or 0),
                    "content": (item.content or "")[:8000],
                    "embedding_model": (item.embedding_model or "")[:100],
                    "vector": [float(value) for value in item.vector],
                }
            )
        self.client.upsert(collection_name=self.collection, data=rows, timeout=self.timeout)
        return len(rows)

    def search(
        self,
        query_vector: list[float],
        user_id: int | None,
        document_ids: list[int],
        parse_record_ids: list[int] | None,
        top_k: int,
    ) -> list[dict[str, Any]]:
        conditions: list[str] = []
        if user_id is not None:
            conditions.append(f"user_id == {int(user_id)}")
        if document_ids:
            ids = ",".join(str(int(value)) for value in document_ids)
            conditions.append(f"document_id in [{ids}]")
        if parse_record_ids:
            ids = ",".join(str(int(value)) for value in parse_record_ids)
            conditions.append(f"parse_record_id in [{ids}]")
        expression = " and ".join(conditions)

        raw = self.client.search(
            collection_name=self.collection,
            data=[[float(value) for value in query_vector]],
            filter=expression,
            limit=max(1, int(top_k)),
            output_fields=[
                "chunk_id",
                "user_id",
                "document_id",
                "parse_record_id",
                "section",
                "page_start",
                "page_end",
                "content",
                "embedding_model",
            ],
            search_params={"metric_type": "COSINE"},
            timeout=self.timeout,
        )

        hits: list[dict[str, Any]] = []
        if not raw:
            return hits
        for hit in raw[0]:
            entity = hit.get("entity") if isinstance(hit, dict) else None
            if entity is None and isinstance(hit, dict):
                entity = hit
            if not isinstance(entity, dict):
                continue
            hits.append(
                {
                    "chunkId": str(entity.get("chunk_id") or hit.get("id") or ""),
                    "userId": int(entity.get("user_id") or 0),
                    "documentId": int(entity.get("document_id") or 0),
                    "parseRecordId": int(entity.get("parse_record_id") or 0),
                    "section": str(entity.get("section") or ""),
                    "pageStart": int(entity.get("page_start") or 0) or None,
                    "pageEnd": int(entity.get("page_end") or 0) or None,
                    "content": str(entity.get("content") or ""),
                    "score": float(hit.get("distance") or 0.0),
                }
            )
        return hits


class VectorStore:
    """向量库门面：优先 Milvus，初始化或运行时失败自动降级本地。"""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self.dim = self.settings.embedding_dim
        self._local = LocalVectorStore(self.settings.local_vector_dir, self.dim)
        self._milvus: MilvusVectorStore | None = None
        self.backend_name = "local"

        if self.settings.vector_store == "milvus":
            try:
                self._milvus = MilvusVectorStore(self.settings)
                self.backend_name = "milvus"
            except Exception as exc:  # noqa: BLE001
                logger.warning("Milvus 初始化失败，回退本地向量库：%s", exc)
        else:
            logger.info("VECTOR_STORE=local，使用本地向量库（%s）", self.settings.local_vector_dir)

        if self.backend_name == "local":
            logger.info("本地向量库当前 %d 条记录", self._local.count())

    @property
    def local_store(self) -> LocalVectorStore:
        return self._local

    def close(self) -> None:
        if self._milvus is not None:
            self._milvus.close()
            self._milvus = None

    def _degrade(self, operation: str, exc: Exception) -> None:
        logger.error("Milvus %s 失败，切换本地向量库：%s", operation, exc)
        if self._milvus is not None:
            self._milvus.close()
        self._milvus = None
        self.backend_name = "local"

    def delete_parse_records(self, document_id: int, keep_parse_record_id: int | None = None) -> int:
        if self._milvus is not None:
            try:
                return self._milvus.delete_parse_records(document_id, keep_parse_record_id)
            except Exception as exc:  # noqa: BLE001
                self._degrade("delete_parse_records", exc)
        return self._local.delete_parse_records(document_id, keep_parse_record_id)

    def delete_documents(self, document_ids: list[int]) -> int:
        if self._milvus is not None:
            try:
                return self._milvus.delete_documents(document_ids)
            except Exception as exc:  # noqa: BLE001
                self._degrade("delete", exc)
        return self._local.delete_documents(document_ids)

    def upsert(self, items: list[VectorItem]) -> int:
        if self._milvus is not None:
            try:
                return self._milvus.upsert(items)
            except Exception as exc:  # noqa: BLE001
                self._degrade("upsert", exc)
        return self._local.upsert(items)

    def search(
        self,
        query_vector: list[float],
        user_id: int | None,
        document_ids: list[int],
        parse_record_ids: list[int] | None,
        top_k: int,
    ) -> list[dict[str, Any]]:
        if self._milvus is not None:
            try:
                return self._milvus.search(
                    query_vector, user_id, document_ids, parse_record_ids, top_k
                )
            except Exception as exc:  # noqa: BLE001
                self._degrade("search", exc)
        return self._local.search(
            query_vector, user_id, document_ids, parse_record_ids, top_k
        )
