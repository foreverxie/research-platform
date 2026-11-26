"""internal v1 API 路由。"""

from .documents import router as documents_router
from .embeddings import router as embeddings_router
from .health import router as health_router
from .rag import router as rag_router
from .search import router as search_router

__all__ = [
    "documents_router",
    "embeddings_router",
    "health_router",
    "rag_router",
    "search_router",
]
