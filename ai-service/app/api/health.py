"""健康检查。"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, Request

from ..config import get_settings
from ..schemas import HealthResponse
from ..security import verify_internal_token
from ..services.parser import effective_parser_name

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/internal/v1",
    tags=["health"],
    dependencies=[Depends(verify_internal_token)],
)


@router.get("/health", response_model=HealthResponse, summary="AI 服务健康检查")
def health(request: Request) -> HealthResponse:
    settings = get_settings()
    services = request.app.state.services
    return HealthResponse(
        status="UP",
        version=settings.app_version,
        parser=effective_parser_name(),
        embedding=settings.effective_embedding_provider,
        llm=settings.effective_llm_provider,
        vectorStore=services.vector_store.backend_name,
    )
