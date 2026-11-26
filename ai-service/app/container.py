"""服务容器。"""

from __future__ import annotations

from dataclasses import dataclass

from .config import Settings
from .services.analyzer import Analyzer
from .services.embedding import EmbeddingService
from .services.indexer import IndexService
from .services.knowledge import KnowledgeExtractor
from .services.llm import LLMService
from .services.metadata import MetadataExtractor
from .services.rag import RagService
from .services.vector_store import VectorStore


@dataclass(slots=True)
class ServiceContainer:
    settings: Settings
    embedding: EmbeddingService
    llm: LLMService
    vector_store: VectorStore
    analyzer: Analyzer
    metadata: MetadataExtractor
    knowledge: KnowledgeExtractor
    rag: RagService
    indexer: IndexService

    def close(self) -> None:
        self.embedding.close()
        self.llm.close()
        self.vector_store.close()
