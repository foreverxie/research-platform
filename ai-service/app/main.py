"""FastAPI 应用入口。

启动：``python -m uvicorn app.main:app --host 0.0.0.0 --port 8000``
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from . import db
from .api import (
    documents_router,
    embeddings_router,
    health_router,
    rag_router,
    search_router,
)
from .config import get_settings
from .container import ServiceContainer
from .services.analyzer import Analyzer
from .services.embedding import EmbeddingService
from .services.indexer import IndexService
from .services.llm import LLMService
from .services.knowledge import KnowledgeExtractor
from .services.metadata import MetadataExtractor
from .services.parser import effective_parser_name
from .services.rag import RagService
from .services.vector_store import VectorStore

logger = logging.getLogger("app.main")


def setup_logging(level_name: str) -> None:
    level = getattr(logging, (level_name or "INFO").upper(), logging.INFO)
    root = logging.getLogger()
    if not root.handlers:
        logging.basicConfig(
            level=level,
            format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
            datefmt="%Y-%m-%d %H:%M:%S",
        )
    else:
        root.setLevel(level)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    logging.getLogger("pymilvus").setLevel(logging.WARNING)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    setup_logging(settings.log_level)

    logger.info("=" * 72)
    logger.info("%s v%s 启动中", settings.app_name, settings.app_version)
    logger.info("配置：%s", settings.describe())
    logger.info("解析器：%s | 内部令牌：%s", effective_parser_name(), "开启" if settings.internal_token else "关闭")

    if db.ping():
        logger.info("MySQL 连接正常（%s:%s/%s）", settings.mysql_host, settings.mysql_port, settings.mysql_database)
    else:
        logger.warning("MySQL 不可用：分析接口将退回使用摘要/标题，向量化状态无法回写")

    vector_store = VectorStore(settings)
    embedding = EmbeddingService(settings)
    llm = LLMService(settings)
    analyzer = Analyzer(llm, settings)
    metadata = MetadataExtractor(llm, settings)
    knowledge = KnowledgeExtractor(llm, settings)
    rag = RagService(embedding, vector_store, llm, settings)
    indexer = IndexService(embedding, vector_store, settings)

    app.state.services = ServiceContainer(
        settings=settings,
        embedding=embedding,
        llm=llm,
        vector_store=vector_store,
        analyzer=analyzer,
        metadata=metadata,
        knowledge=knowledge,
        rag=rag,
        indexer=indexer,
    )

    logger.info(
        "就绪：/internal/v1/health | 向量库=%s | LLM=%s | Embedding=%s | 端口以 uvicorn 启动参数为准",
        vector_store.backend_name,
        "remote" if llm.available else "local",
        embedding.provider,
    )
    logger.info("=" * 72)

    try:
        yield
    finally:
        logger.info("正在关闭服务……")
        app.state.services.close()
        logger.info("服务已停止")


app = FastAPI(
    title="ScholarAI AI Service",
    description="科研文献解析、结构化分析、语义检索与 RAG 问答的内部服务",
    version=get_settings().app_version,
    lifespan=lifespan,
)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    details = []
    for error in exc.errors():
        location = ".".join(str(part) for part in error.get("loc", ()))
        details.append(f"{location}: {error.get('msg', '参数不合法')}")
    logger.warning("请求参数校验失败：%s %s -> %s", request.method, request.url.path, details)
    return JSONResponse(status_code=400, content={"detail": "请求参数不合法：" + "；".join(details)})


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    # 不把堆栈返回给调用方，只写日志
    logger.exception("未处理异常：%s %s", request.method, request.url.path)
    return JSONResponse(
        status_code=500,
        content={"detail": "AI 服务内部错误，请查看服务日志"},
    )


app.include_router(health_router)
app.include_router(documents_router)
app.include_router(rag_router)
app.include_router(search_router)
app.include_router(embeddings_router)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=get_settings().app_port,
        log_level=get_settings().log_level.lower(),
    )
