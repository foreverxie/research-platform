"""语义检索接口。"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, Request

from ..schemas import SemanticSearchRequest, SemanticSearchResponse
from ..security import verify_internal_token

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/internal/v1",
    tags=["search"],
    dependencies=[Depends(verify_internal_token)],
)


@router.post("/search/semantic", response_model=SemanticSearchResponse, summary="文本块语义检索")
def semantic_search(request: SemanticSearchRequest, http_request: Request) -> SemanticSearchResponse:
    services = http_request.app.state.services
    hits = services.rag.semantic_search(request)
    return SemanticSearchResponse(hits=hits)
