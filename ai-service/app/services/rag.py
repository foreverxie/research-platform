"""语义检索与 RAG 问答。"""

from __future__ import annotations

import logging
import re
from typing import Any

from fastapi import HTTPException

from ..config import Settings, get_settings
from ..schemas import (
    RagReference,
    RagRequest,
    RagResponse,
    SearchHit,
    SemanticSearchRequest,
)
from .embedding import EmbeddingError, EmbeddingService
from .llm import LLMError, LLMService
from .vector_store import VectorStore

logger = logging.getLogger(__name__)

LOCAL_MODEL = "local-fallback"
_WHITESPACE_RE = re.compile(r"\s+")


def _clamp_top_k(value: int | None, default: int = 5, maximum: int = 20) -> int:
    try:
        parsed = int(value) if value is not None else default
    except (TypeError, ValueError):
        parsed = default
    if parsed <= 0:
        parsed = default
    return max(1, min(parsed, maximum))


def _normalize_ids(values: list[int] | None) -> list[int]:
    result: list[int] = []
    for value in values or []:
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            continue
        if parsed not in result:
            result.append(parsed)
    return result


def _normalize_document_ids(document_ids: list[int] | None) -> list[int]:
    return _normalize_ids(document_ids)


def _normalize_parse_record_ids(parse_record_ids: list[int] | None) -> list[int]:
    return _normalize_ids(parse_record_ids)


def _clean_text(text: str) -> str:
    return _WHITESPACE_RE.sub(" ", (text or "")).strip()


def _snippet(content: str, query: str, limit: int = 220) -> str:
    text = _clean_text(content)
    if not text:
        return ""

    position = -1
    lowered = text.lower()
    for size in (4, 3, 2):
        for index in range(0, max(0, len(query) - size + 1)):
            piece = query[index : index + size].strip()
            if len(piece) < 2:
                continue
            found = lowered.find(piece.lower())
            if found >= 0 and (position < 0 or found < position):
                position = found
    start = max(0, position - 80) if position > 80 else 0
    piece = text[start : start + limit]
    if start > 0:
        piece = "…" + piece
    if start + limit < len(text):
        piece = piece + "…"
    return piece


class RagService:
    def __init__(
        self,
        embedding: EmbeddingService,
        vector_store: VectorStore,
        llm: LLMService,
        settings: Settings | None = None,
    ) -> None:
        self.embedding = embedding
        self.vector_store = vector_store
        self.llm = llm
        self.settings = settings or get_settings()

    # ------------------------------------------------------------------ 检索
    def _search(
        self,
        query: str,
        user_id: int,
        document_ids: list[int],
        parse_record_ids: list[int],
        top_k: int,
    ) -> list[dict[str, Any]]:
        if not query or not query.strip():
            raise HTTPException(status_code=400, detail="检索内容不能为空")
        try:
            vector = self.embedding.embed_query(query)
        except EmbeddingError as exc:
            logger.error("查询向量化失败：%s", exc)
            raise HTTPException(status_code=503, detail=f"向量模型不可用：{exc}") from exc

        try:
            return self.vector_store.search(
                query_vector=vector,
                user_id=user_id,
                document_ids=document_ids,
                parse_record_ids=parse_record_ids,
                top_k=top_k,
            )
        except Exception as exc:  # noqa: BLE001
            logger.exception("向量检索失败")
            raise HTTPException(status_code=503, detail=f"向量检索失败：{exc}") from exc

    def semantic_search(self, request: SemanticSearchRequest) -> list[SearchHit]:
        document_ids = _normalize_document_ids(request.documentIds)
        parse_record_ids = _normalize_parse_record_ids(request.parseRecordIds)
        top_k = _clamp_top_k(request.topK)
        results = self._search(
            request.query, request.userId, document_ids, parse_record_ids, top_k
        )

        hits: list[SearchHit] = []
        for item in results:
            hits.append(
                SearchHit(
                    documentId=int(item.get("documentId") or 0),
                    chunkId=str(item.get("chunkId") or ""),
                    section=str(item.get("section") or ""),
                    pageStart=item.get("pageStart"),
                    pageEnd=item.get("pageEnd"),
                    content=str(item.get("content") or ""),
                    score=round(float(item.get("score") or 0.0), 6),
                )
            )
        return hits

    # ------------------------------------------------------------------ 问答
    def answer(self, request: RagRequest) -> RagResponse:
        question = (request.question or "").strip()
        if not question:
            raise HTTPException(status_code=400, detail="问题不能为空")

        document_ids = _normalize_document_ids(request.documentIds)
        parse_record_ids = _normalize_parse_record_ids(request.parseRecordIds)
        top_k = _clamp_top_k(request.topK)
        results = self._search(
            question, request.userId, document_ids, parse_record_ids, top_k
        )

        references = self._build_references(results, question)
        if not references:
            return RagResponse(
                answer=(
                    "根据文献库检索，未找到与该问题直接相关的原文证据，暂时无法回答。"
                    "请确认相关文献已完成解析和向量化，或换一种问法后重试。"
                ),
                modelName=LOCAL_MODEL,
                promptTokens=0,
                completionTokens=0,
                references=[],
            )

        if self.llm.available:
            try:
                return self._llm_answer(question, references, results)
            except (LLMError, ValueError, KeyError, TypeError) as exc:
                logger.warning("LLM 问答失败，改用抽取式回答：%s", exc)
            except Exception as exc:  # noqa: BLE001
                logger.warning("LLM 问答异常，改用抽取式回答：%s", exc)

        return self._extractive_answer(references)

    def _build_references(
        self, results: list[dict[str, Any]], query: str
    ) -> list[RagReference]:
        references: list[RagReference] = []
        seen: set[str] = set()
        for item in results:
            chunk_id = str(item.get("chunkId") or "")
            if chunk_id and chunk_id in seen:
                continue
            seen.add(chunk_id)
            content = str(item.get("content") or "")
            page_start = item.get("pageStart")
            page_end = item.get("pageEnd")
            page_number = page_start or page_end
            references.append(
                RagReference(
                    documentId=int(item.get("documentId") or 0),
                    chunkId=chunk_id,
                    pageNumber=int(page_number) if page_number else None,
                    section=str(item.get("section") or ""),
                    relevanceScore=round(float(item.get("score") or 0.0), 6),
                    snippet=_snippet(content, query),
                )
            )
        return references

    @staticmethod
    def _page_label(page_number: int | None) -> str:
        return f"第 {page_number} 页" if page_number else "页码未标注"

    @staticmethod
    def _section_label(section: str) -> str:
        return section.strip() or "未标注章节"

    def _extractive_answer(self, references: list[RagReference]) -> RagResponse:
        lines = [
            f"根据文献库检索到的 {len(references)} 段相关原文，可以整理出以下证据"
            "（抽取式回答，未做超出原文的推断）：",
            "",
        ]
        for index, reference in enumerate(references, start=1):
            lines.append(
                f"{index}. 文献 {reference.documentId}｜"
                f"{self._section_label(reference.section)}｜"
                f"{self._page_label(reference.pageNumber)}：{reference.snippet}"
            )
        lines.extend(
            [
                "",
                "以上内容均直接摘自检索到的原文片段，请结合原文核对后使用。",
            ]
        )
        return RagResponse(
            answer="\n".join(lines),
            modelName=LOCAL_MODEL,
            promptTokens=0,
            completionTokens=0,
            references=references,
        )

    def _llm_answer(
        self,
        question: str,
        references: list[RagReference],
        results: list[dict[str, Any]],
    ) -> RagResponse:
        content_by_chunk = {
            str(item.get("chunkId") or ""): _clean_text(str(item.get("content") or ""))
            for item in results
        }
        evidence_lines: list[str] = []
        total_chars = 0
        for index, reference in enumerate(references, start=1):
            content = content_by_chunk.get(reference.chunkId) or reference.snippet
            content = content[:1200]
            if total_chars + len(content) > self.settings.rag_max_context_chars and evidence_lines:
                break
            evidence_lines.append(
                f"[{index}] 文献ID={reference.documentId}｜"
                f"章节={self._section_label(reference.section)}｜"
                f"页码={reference.pageNumber or '未标注'}\n{content}"
            )
            total_chars += len(content)

        system = (
            "你是一名严谨的科研文献问答助手。只能依据给出的检索原文回答，"
            "不得编造、不得引入原文之外的事实。回答使用中文 Markdown，"
            "引用证据时使用 [1]、[2] 这样的编号。若证据不足以回答，要明确说明证据不足。"
        )
        user = (
            f"问题：{question}\n\n"
            f"检索到的原文证据：\n\n" + "\n\n".join(evidence_lines) + "\n\n"
            "请基于以上证据作答，并在关键结论后用 [编号] 标注引用来源。"
        )

        result = self.llm.chat(
            messages=[
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ]
        )
        answer = (result.text or "").strip()
        if not answer:
            raise LLMError("LLM 返回空回答")

        return RagResponse(
            answer=answer,
            modelName=result.model or self.llm.model_name,
            promptTokens=result.prompt_tokens,
            completionTokens=result.completion_tokens,
            references=references,
        )
