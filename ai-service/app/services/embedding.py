"""Embedding 服务。

- 配置了真实 Key 时走 DashScope / OpenAI 兼容的 ``/embeddings`` 接口；
- 没有 Key 或显式选择 local 时，使用基于 sha256 分词哈希的确定性本地向量，
  维度固定（默认 1024），L2 归一化，无需下载模型。
"""

from __future__ import annotations

import hashlib
import logging
import re
import time
from collections.abc import Iterable

import httpx
import numpy as np

from ..config import Settings, get_settings

logger = logging.getLogger(__name__)

_CJK_RUN_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]+")
_EN_WORD_RE = re.compile(r"[a-zA-Z][a-zA-Z0-9_\-]+")

# 常见虚词：参与哈希只会引入噪声
_CN_STOP_CHARS = set("的了是在和与及对为中上下这那有被把将从到等就也都而及其之")
_CN_STOP_WORDS = {
    "一个", "我们", "你们", "他们", "可以", "如何", "什么", "为什么", "请问",
    "这些", "那些", "这个", "那个", "已经", "通过", "以及", "但是", "因此",
    "由于", "如果", "其中", "对于", "不是", "没有", "能够", "进行", "使用",
}
_EN_STOP_WORDS = {
    "the", "and", "for", "with", "that", "this", "these", "those", "from", "into",
    "are", "was", "were", "been", "being", "have", "has", "had", "not", "but",
    "its", "our", "their", "his", "her", "they", "them", "you", "your", "can",
    "could", "would", "should", "may", "might", "will", "shall", "than", "then",
    "there", "here", "which", "who", "whom", "what", "when", "where", "why", "how",
    "also", "such", "more", "most", "some", "any", "all", "each", "other", "only",
    "about", "over", "under", "between", "within", "without", "using", "based",
    "paper", "study", "results", "method", "methods", "approach", "propose",
}


class EmbeddingError(RuntimeError):
    """Embedding 调用失败。"""


def _iter_local_tokens(text: str) -> Iterable[tuple[str, float]]:
    lowered = text.lower()

    for match in _EN_WORD_RE.finditer(lowered):
        word = match.group(0)
        if len(word) < 2 or word in _EN_STOP_WORDS:
            continue
        yield word, 1.0

    for match in _CJK_RUN_RE.finditer(lowered):
        run = match.group(0)
        for char in run:
            if char not in _CN_STOP_CHARS:
                yield char, 0.5
        for size, weight in ((2, 1.0), (3, 0.8)):
            if len(run) < size:
                continue
            for index in range(len(run) - size + 1):
                gram = run[index : index + size]
                if gram in _CN_STOP_WORDS:
                    continue
                if all(ch in _CN_STOP_CHARS for ch in gram):
                    continue
                yield gram, weight


def _normalize_vector(vector: np.ndarray) -> np.ndarray:
    norm = float(np.linalg.norm(vector))
    if norm <= 0:
        return vector.astype(np.float32)
    return (vector / norm).astype(np.float32)


def local_hash_embedding(text: str, dim: int) -> list[float]:
    """确定性哈希向量：相同文本永远得到相同向量。"""
    vector = np.zeros(dim, dtype=np.float32)
    for token, weight in _iter_local_tokens(text):
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        index = int.from_bytes(digest[:8], "little") % dim
        sign = 1.0 if digest[8] & 1 else -1.0
        vector[index] += sign * weight

    if not np.any(vector):
        # 极端情况（空文本/全是停用词）也要给出稳定向量
        digest = hashlib.sha256((text or "").encode("utf-8")).digest()
        index = int.from_bytes(digest[:8], "little") % dim
        vector[index] = 1.0

    return _normalize_vector(vector).tolist()


class EmbeddingService:
    """统一的文本向量化入口。"""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self.provider = self.settings.effective_embedding_provider
        self.dim = self.settings.embedding_dim
        self.model_name = self.settings.effective_embedding_model
        self.batch_size = max(1, min(int(self.settings.embedding_batch_size), 64))
        self._client: httpx.Client | None = None

        if self.provider == "local":
            logger.info("Embedding 使用本地哈希向量（dim=%d, model=%s）", self.dim, self.model_name)
        else:
            logger.info(
                "Embedding 使用远程模型（provider=%s, model=%s, dim=%d）",
                self.provider,
                self.model_name,
                self.dim,
            )

    # ------------------------------------------------------------- 对外接口
    @property
    def is_remote(self) -> bool:
        return self.provider in ("dashscope", "openai")

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        if not self.is_remote:
            return [local_hash_embedding(text, self.dim) for text in texts]

        vectors: list[list[float]] = []
        for start in range(0, len(texts), self.batch_size):
            batch = texts[start : start + self.batch_size]
            vectors.extend(self._remote_embeddings(batch))
        return vectors

    def embed_query(self, text: str) -> list[float]:
        if not text or not text.strip():
            raise EmbeddingError("待向量化文本不能为空")
        if not self.is_remote:
            return local_hash_embedding(text, self.dim)
        return self._remote_embeddings([text])[0]

    def close(self) -> None:
        if self._client is not None:
            self._client.close()
            self._client = None

    # ------------------------------------------------------------- 远程实现
    @property
    def _http(self) -> httpx.Client:
        if self._client is None:
            self._client = httpx.Client(
                timeout=httpx.Timeout(self.settings.request_timeout_seconds, connect=10.0)
            )
        return self._client

    def _remote_embeddings(self, texts: list[str]) -> list[list[float]]:
        url = f"{self.settings.embedding_base_url}/embeddings"
        headers = {
            "Authorization": f"Bearer {self.settings.embedding_api_key}",
            "Content-Type": "application/json",
        }
        payload: dict[str, object] = {
            "model": self.model_name,
            "input": texts,
            "encoding_format": "float",
            "dimensions": self.dim,
        }

        response = self._post_with_retry(url, headers, payload)
        if response.status_code == 400 and "dimension" in response.text.lower():
            logger.warning("模型不支持 dimensions 参数，去掉后重试")
            payload.pop("dimensions", None)
            response = self._post_with_retry(url, headers, payload)

        if response.status_code >= 400:
            body = response.text[:500]
            raise EmbeddingError(
                f"Embedding 接口返回 {response.status_code}：{body}"
            )

        try:
            data = response.json()
            items = data.get("data") or []
        except Exception as exc:  # noqa: BLE001
            raise EmbeddingError(f"Embedding 响应不是合法 JSON：{exc}") from exc

        if not isinstance(items, list) or len(items) != len(texts):
            raise EmbeddingError(
                f"Embedding 返回条数异常：期望 {len(texts)}，实际 {len(items) if isinstance(items, list) else 'N/A'}"
            )

        items = sorted(items, key=lambda item: item.get("index", 0))
        vectors: list[list[float]] = []
        for item in items:
            raw = item.get("embedding")
            if not isinstance(raw, list) or not raw:
                raise EmbeddingError("Embedding 返回了空向量")
            if len(raw) != self.dim:
                raise EmbeddingError(
                    f"Embedding 维度不匹配：配置 EMBEDDING_DIM={self.dim}，模型返回 {len(raw)}；"
                    "请调整 EMBEDDING_DIM 或更换模型"
                )
            vectors.append(_normalize_vector(np.asarray(raw, dtype=np.float32)).tolist())
        return vectors

    def _post_with_retry(
        self,
        url: str,
        headers: dict[str, str],
        payload: dict[str, object],
    ) -> httpx.Response:
        last_error: Exception | None = None
        for attempt in range(2):
            try:
                response = self._http.post(url, headers=headers, json=payload)
            except httpx.HTTPError as exc:
                last_error = exc
                logger.warning("Embedding 请求失败（第 %d 次）：%s", attempt + 1, exc)
                time.sleep(0.8 * (attempt + 1))
                continue

            if response.status_code in (429, 500, 502, 503, 504) and attempt == 0:
                logger.warning("Embedding 接口返回 %s，准备重试", response.status_code)
                time.sleep(0.8)
                continue
            return response

        raise EmbeddingError(f"Embedding 请求失败：{last_error}")
