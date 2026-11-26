"""文本块向量化入库（/internal/v1/embeddings/index）。"""

from __future__ import annotations

import logging

from .. import db
from ..config import Settings, get_settings
from ..schemas import IndexChunk, IndexResponse
from .embedding import EmbeddingError, EmbeddingService
from .vector_store import VectorItem, VectorStore

logger = logging.getLogger(__name__)


class IndexService:
    def __init__(
        self,
        embedding: EmbeddingService,
        vector_store: VectorStore,
        settings: Settings | None = None,
    ) -> None:
        self.embedding = embedding
        self.vector_store = vector_store
        self.settings = settings or get_settings()

    def index(self, chunks: list[IndexChunk]) -> IndexResponse:
        if not chunks:
            return IndexResponse(indexed=0, failed=0, message="没有待索引的文本块")

        # 同一 chunkId 以最后一条为准；空内容/空 ID 直接记为失败
        deduped: dict[str, IndexChunk] = {}
        failed_ids: list[str] = []
        for chunk in chunks:
            chunk_id = (chunk.chunkId or "").strip()
            if not chunk_id or not (chunk.content or "").strip():
                if chunk_id:
                    failed_ids.append(chunk_id)
                continue
            deduped[chunk_id] = chunk

        if not deduped:
            return IndexResponse(
                indexed=0,
                failed=len(failed_ids),
                message="所有文本块都缺少 chunkId 或 content",
            )

        items = list(deduped.values())

        # 注意：这里不能先删旧向量。
        # 新版本必须先完成写入并通过完整性检查，切换当前版本后才清理旧版本。
        success: list[VectorItem] = []
        error_message = ""
        batch_size = max(1, min(int(self.settings.embedding_batch_size), 64))

        for start in range(0, len(items), batch_size):
            batch = items[start : start + batch_size]
            try:
                vectors = self.embedding.embed_documents([item.content for item in batch])
                if len(vectors) != len(batch):
                    raise EmbeddingError(
                        f"向量条数不匹配：期望 {len(batch)}，实际 {len(vectors)}"
                    )
            except EmbeddingError as exc:
                logger.error("文本块向量化失败：%s", exc)
                error_message = str(exc)
                failed_ids.extend(item.chunkId for item in batch)
                continue
            except Exception as exc:  # noqa: BLE001
                logger.exception("文本块向量化异常")
                error_message = str(exc)
                failed_ids.extend(item.chunkId for item in batch)
                continue

            for chunk, vector in zip(batch, vectors, strict=False):
                success.append(
                    VectorItem(
                        chunk_id=chunk.chunkId,
                        user_id=int(chunk.userId),
                        document_id=int(chunk.documentId),
                        parse_record_id=int(chunk.parseRecordId),
                        section=chunk.section or "",
                        page_start=chunk.pageStart,
                        page_end=chunk.pageEnd,
                        content=chunk.content,
                        embedding_model=self.embedding.model_name,
                        vector=vector,
                    )
                )

        indexed = 0
        if success:
            try:
                indexed = self.vector_store.upsert(success)
            except Exception as exc:  # noqa: BLE001
                logger.exception("写入向量库失败")
                error_message = f"写入向量库失败：{exc}"
                failed_ids.extend(item.chunk_id for item in success)
                success = []
                indexed = 0

        ok_ids = [item.chunk_id for item in success]
        failed_ids = list(dict.fromkeys(failed_ids))
        model_name = self.embedding.model_name

        try:
            if ok_ids:
                db.update_embedding_status([(chunk_id, "SUCCESS", model_name) for chunk_id in ok_ids])
            if failed_ids:
                db.update_embedding_status([(chunk_id, "FAILED", model_name) for chunk_id in failed_ids])
        except Exception as exc:  # noqa: BLE001
            # 数据库状态更新失败不能影响解析任务
            logger.warning("更新 document_chunk.embedding_status 失败：%s", exc)

        message = None
        if failed_ids or error_message:
            message = (
                f"成功 {indexed} 条，失败 {len(failed_ids)} 条"
                + (f"：{error_message}" if error_message else "")
            )

        logger.info(
            "向量化完成：indexed=%d, failed=%d, documents=%s, model=%s, backend=%s",
            indexed,
            len(failed_ids),
            sorted({int(item.documentId) for item in items}),
            model_name,
            self.vector_store.backend_name,
        )
        return IndexResponse(indexed=indexed, failed=len(failed_ids), message=message)
