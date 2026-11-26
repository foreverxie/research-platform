"""PDF 解析与文献分析接口。"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, Request

from ..schemas import (
    AnalyzeRequest,
    AnalyzeResponse,
    KnowledgeRequest,
    KnowledgeResponse,
    MetadataRequest,
    MetadataResponse,
    ParseRequest,
    ParseResponse,
    ParsedChunk,
)
from ..security import verify_internal_token
from ..services.parser import parse_pdf

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/internal/v1",
    tags=["documents"],
    dependencies=[Depends(verify_internal_token)],
)


@router.post("/documents/parse", response_model=ParseResponse, summary="解析 PDF 为 Markdown 与文本块")
def parse_document(request: ParseRequest) -> ParseResponse:
    result = parse_pdf(request.filePath, request.fileName)
    return ParseResponse(
        markdown=result.markdown,
        json_text=result.json_text,
        pageCount=result.page_count,
        parserName=result.parser_name,
        parserVersion=result.parser_version,
        chunks=[
            ParsedChunk(
                section=chunk.section,
                pageStart=chunk.page_start,
                pageEnd=chunk.page_end,
                content=chunk.content,
                tokenCount=chunk.token_count,
            )
            for chunk in result.chunks
        ],
    )


@router.post("/documents/analyze", response_model=AnalyzeResponse, summary="文献结构化分析")
def analyze_document(request: AnalyzeRequest, http_request: Request) -> AnalyzeResponse:
    services = http_request.app.state.services
    return services.analyzer.analyze(request)


@router.post(
    "/documents/metadata",
    response_model=MetadataResponse,
    summary="从 PDF 与首页正文识别文献元数据",
)
def extract_metadata(request: MetadataRequest, http_request: Request) -> MetadataResponse:
    services = http_request.app.state.services
    return services.metadata.extract(request)


@router.post(
    "/knowledge/extract",
    response_model=KnowledgeResponse,
    summary="从论文原文抽取知识实体与关系",
)
def extract_knowledge(request: KnowledgeRequest, http_request: Request) -> KnowledgeResponse:
    services = http_request.app.state.services
    return services.knowledge.extract(request)
