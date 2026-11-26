"""RAG 问答接口。"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, Request

from ..schemas import RagRequest, RagResponse
from ..security import verify_internal_token

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/internal/v1",
    tags=["rag"],
    dependencies=[Depends(verify_internal_token)],
)


@router.post("/rag/answer", response_model=RagResponse, summary="基于文献库的 RAG 问答")
def rag_answer(request: RagRequest, http_request: Request) -> RagResponse:
    services = http_request.app.state.services
    return services.rag.answer(request)
