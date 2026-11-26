"""文本块向量化接口。

解析任务成功后由 Java 后端调用；嵌入失败返回 200 + indexed=0，
避免把解析任务连带标记为失败。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, Request

from ..schemas import CleanupRequest, CleanupResponse, IndexRequest, IndexResponse
from ..security import verify_internal_token

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/internal/v1",
    tags=["embeddings"],
    dependencies=[Depends(verify_internal_token)],
)


@router.post("/embeddings/index", response_model=IndexResponse, summary="批量写入文本块向量")
def index_embeddings(request: IndexRequest, http_request: Request) -> IndexResponse:
    services = http_request.app.state.services
    chunks = request.chunks or []
    try:
        return services.indexer.index(chunks)
    except Exception as exc:  # noqa: BLE001 - 向量化失败不应让解析任务失败
        logger.exception("向量化接口异常")
        return IndexResponse(
            indexed=0,
            failed=len(chunks),
            message=f"向量化失败：{exc}",
        )


@router.post(
    "/embeddings/cleanup",
    response_model=CleanupResponse,
    summary="清理某文献除当前版本外的旧向量",
)
def cleanup_embeddings(request: CleanupRequest, http_request: Request) -> CleanupResponse:
    services = http_request.app.state.services
    try:
        deleted = services.vector_store.delete_parse_records(
            request.documentId,
            request.keepParseRecordId,
        )
        return CleanupResponse(deleted=deleted)
    except Exception as exc:  # noqa: BLE001
        logger.exception("旧向量清理失败")
        return CleanupResponse(deleted=0, message=f"清理失败：{exc}")
