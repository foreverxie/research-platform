"""与 Java 后端 DTO 严格对齐的请求/响应模型。

字段名保持 camelCase，与
``com.research.platform.infrastructure.ai.AiCommands/AiResults`` 一致。
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class ApiModel(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)


# --------------------------------------------------------------------- 健康检查
class HealthResponse(ApiModel):
    status: str = "UP"
    version: str
    parser: str
    embedding: str
    llm: str
    vectorStore: str


# ----------------------------------------------------------------------- 解析
class ParseRequest(ApiModel):
    documentId: int
    parseRecordId: int | None = None
    taskId: int | None = None
    filePath: str
    fileName: str | None = None


class ParsedChunk(ApiModel):
    section: str
    pageStart: int | None = None
    pageEnd: int | None = None
    content: str
    tokenCount: int = 0


class ParseResponse(ApiModel):
    markdown: str
    # BaseModel 自带 json() 方法，这里用别名避免字段名冲突
    json_text: str = Field(alias="json")
    pageCount: int = 0
    parserName: str = "PyMuPDF"
    parserVersion: str = ""
    chunks: list[ParsedChunk] = Field(default_factory=list)


# ----------------------------------------------------------------------- 分析
class AnalyzeRequest(ApiModel):
    documentId: int
    taskId: int | None = None
    analysisType: str | None = "FULL_ANALYSIS"
    title: str | None = None
    abstractText: str | None = None
    markdownPath: str | None = None


class AnalyzeResponse(ApiModel):
    summary: str
    researchBackground: str
    researchMethod: str
    innovationPoints: list[str] = Field(default_factory=list)
    experimentalResults: str
    conclusion: str
    keywords: list[str] = Field(default_factory=list)
    modelName: str
    promptTokens: int = 0
    completionTokens: int = 0


# ------------------------------------------------------------------- RAG 问答
class RagRequest(ApiModel):
    userId: int
    question: str
    documentIds: list[int] | None = None
    parseRecordIds: list[int] | None = None
    topK: int = 5


class RagReference(ApiModel):
    documentId: int
    chunkId: str
    pageNumber: int | None = None
    section: str = ""
    relevanceScore: float = 0.0
    snippet: str = ""


class RagResponse(ApiModel):
    answer: str
    modelName: str
    promptTokens: int = 0
    completionTokens: int = 0
    references: list[RagReference] = Field(default_factory=list)


# --------------------------------------------------------------------- 语义检索
class SemanticSearchRequest(ApiModel):
    userId: int
    query: str
    documentIds: list[int] | None = None
    parseRecordIds: list[int] | None = None
    topK: int = 5


class SearchHit(ApiModel):
    documentId: int
    chunkId: str
    section: str = ""
    pageStart: int | None = None
    pageEnd: int | None = None
    content: str = ""
    score: float = 0.0


class SemanticSearchResponse(ApiModel):
    hits: list[SearchHit] = Field(default_factory=list)


# ------------------------------------------------------------------- 元数据识别
class MetadataRequest(ApiModel):
    documentId: int
    parseRecordId: int | None = None
    filePath: str | None = None
    fileName: str | None = None
    markdown: str | None = None
    title: str | None = None


class MetadataResponse(ApiModel):
    title: str
    authors: list[str] = Field(default_factory=list)
    journal: str | None = None
    doi: str | None = None
    publicationYear: int | None = None
    abstractText: str | None = None
    keywords: list[str] = Field(default_factory=list)
    documentType: str = "JOURNAL"
    language: str = "英文"
    modelName: str = "local-fallback"


# --------------------------------------------------------------------- 向量化
class IndexChunk(ApiModel):
    chunkId: str
    userId: int
    documentId: int
    parseRecordId: int
    section: str | None = None
    pageStart: int | None = None
    pageEnd: int | None = None
    content: str | None = None


class IndexRequest(ApiModel):
    chunks: list[IndexChunk] | None = None


class IndexResponse(ApiModel):
    indexed: int = 0
    failed: int = 0
    message: str | None = None


class CleanupRequest(ApiModel):
    documentId: int
    keepParseRecordId: int | None = None


class CleanupResponse(ApiModel):
    deleted: int = 0
    message: str | None = None


# ------------------------------------------------------------------- 知识抽取
class KnowledgeRequest(ApiModel):
    documentId: int
    parseRecordId: int | None = None
    markdownPath: str | None = None
    title: str | None = None


class KnowledgeEntity(ApiModel):
    name: str
    type: str
    description: str | None = None
    confidence: float = 1.0
    evidence: str | None = None


class KnowledgeRelation(ApiModel):
    sourceName: str
    sourceType: str
    targetName: str
    targetType: str
    relationType: str
    confidence: float = 0.8
    evidence: str | None = None


class KnowledgeResponse(ApiModel):
    entities: list[KnowledgeEntity] = Field(default_factory=list)
    relations: list[KnowledgeRelation] = Field(default_factory=list)
    modelName: str = "local-fallback"


# ------------------------------------------------------------------- 类型别名
EmbeddingProvider = Literal["local", "dashscope", "openai"]
LLMProvider = Literal["local", "dashscope", "openai"]
