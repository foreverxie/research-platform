"""知识实体/关系抽取：有 LLM 走模型，无 Key 只保留论文节点。"""

from __future__ import annotations

import json
import logging
import re

from fastapi import HTTPException

from .. import db
from ..config import Settings, get_settings
from ..schemas import (
    KnowledgeEntity,
    KnowledgeRelation,
    KnowledgeRequest,
    KnowledgeResponse,
)
from .llm import ChatResult, LLMError, LLMService
from .parser import resolve_storage_file

logger = logging.getLogger(__name__)

LOCAL_MODEL = "local-fallback"
_MAX_TEXT = 14000
_MAX_ENTITIES = 40
_MAX_RELATIONS = 80

_ENTITY_TYPES = {
    "PAPER", "AUTHOR", "TOPIC", "METHOD", "MODEL", "DATASET", "INSTITUTION",
}
_RELATION_TYPES = {
    "AUTHORED", "STUDIES", "USES_METHOD", "USES_DATASET", "CITES", "AFFILIATED",
}
_RELATION_ALIASES = {
    "USES_MODEL": "USES_METHOD",
    "USES_ALGORITHM": "USES_METHOD",
    "USES": "USES_METHOD",
    "BELONGS_TO": "AFFILIATED",
}


def _clean(value: object) -> str:
    return re.sub(r"\s+", " ", str(value or "")).strip()


def _clamp(value: object, default: float = 0.8) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return default
    return max(0.0, min(1.0, parsed))


def _json_object(content: str) -> dict:
    text = (content or "").strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.I)
        text = re.sub(r"\s*```$", "", text)
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("模型输出中没有 JSON 对象")

    candidate = text[start : end + 1]
    try:
        data = json.loads(candidate)
    except json.JSONDecodeError:
        # qwen 偶尔输出未转义换行/尾逗号，先修复再解析
        from json_repair import repair_json

        data = json.loads(repair_json(candidate))

    if not isinstance(data, dict):
        raise ValueError("模型输出不是 JSON 对象")
    return data


class KnowledgeExtractor:
    def __init__(self, llm: LLMService, settings: Settings | None = None) -> None:
        self.llm = llm
        self.settings = settings or get_settings()

    def extract(self, request: KnowledgeRequest) -> KnowledgeResponse:
        markdown = self._load_markdown(request)
        title = _clean(request.title)

        if not title and request.documentId:
            meta = db.get_document_meta(request.documentId) or {}
            title = _clean(meta.get("title"))

        title = title or "未命名文献"

        if self.llm.available and markdown.strip():
            try:
                result = self._llm_extract(title, markdown[: _MAX_TEXT])
                logger.info(
                    "知识抽取完成（LLM）：documentId=%s, entities=%d, relations=%d, model=%s",
                    request.documentId,
                    len(result.entities),
                    len(result.relations),
                    result.modelName,
                )
                return result
            except (LLMError, ValueError, KeyError, TypeError) as exc:
                logger.warning("知识抽取 LLM 失败，使用最小图谱：%s", exc)
            except Exception as exc:  # noqa: BLE001
                logger.warning("知识抽取 LLM 异常，使用最小图谱：%s", exc)

        return KnowledgeResponse(
            entities=[
                KnowledgeEntity(
                    name=title,
                    type="PAPER",
                    description=markdown[:200] or None,
                    confidence=1.0,
                    evidence="文献标题",
                )
            ],
            relations=[],
            modelName=LOCAL_MODEL,
        )

    def _load_markdown(self, request: KnowledgeRequest) -> str:
        relative_path = (request.markdownPath or "").strip()
        if not relative_path and request.documentId:
            try:
                relative_path = db.resolve_markdown_path(request.documentId) or ""
            except Exception as exc:  # noqa: BLE001
                logger.warning("反查 Markdown 路径失败：%s", exc)

        if not relative_path:
            return ""

        try:
            path = resolve_storage_file(relative_path)
            return path.read_text(encoding="utf-8", errors="replace")
        except HTTPException as exc:
            logger.warning("读取 Markdown 失败：%s", exc.detail)
        except OSError as exc:
            logger.warning("读取 Markdown 失败：%s", exc)
        return ""

    # -------------------------------------------------------------------- LLM
    def _llm_extract(self, title: str, markdown: str) -> KnowledgeResponse:
        """实体、关系分两次调用，避免一次输出过长被截断。"""
        entity_system = (
            "你是科研知识图谱抽取助手。只依据给定论文原文抽取实体，"
            "无法确认的内容不要编造；输出必须是合法 JSON，不要 Markdown 代码块。"
        )
        entity_user = f"""论文标题：{title}

原文（可能被截断）：
{markdown}

请只抽取知识实体，输出 JSON：
{{
  "entities": [
    {{"name": "实体名", "type": "PAPER|AUTHOR|TOPIC|METHOD|MODEL|DATASET|INSTITUTION", "description": "简述", "confidence": 0.0-1.0, "evidence": "原文依据"}}
  ]
}}

要求：
1. 必须有一个 type=PAPER、name 等于论文标题的实体。
2. 方法用 METHOD，算法/模型用 MODEL，数据集/benchmark 用 DATASET，机构用 INSTITUTION，研究主题用 TOPIC。
3. 名称简洁，不要重复；实体最多 30 个，description/evidence 各不超过 80 字。"""

        entity_result: ChatResult = self.llm.chat(
            messages=[
                {"role": "system", "content": entity_system},
                {"role": "user", "content": entity_user},
            ],
            temperature=0.1,
            max_tokens=2400,
        )
        entity_data = _json_object(entity_result.text)
        entities = self._normalize_entities(entity_data.get("entities"), title)
        model_name = entity_result.model or self.llm.model_name

        logger.info(
            "知识抽取实体结果：entities=%d, title=%s",
            len(entities),
            title,
        )

        if not entities:
            return KnowledgeResponse(entities=[], relations=[], modelName=model_name)

        entity_lines = "\n".join(
            f"- {entity.name}（{entity.type}）" for entity in entities
        )
        relation_system = (
            "你是科研知识图谱关系抽取助手。只使用给定的实体列表和论文原文建立关系，"
            "无法确认的关系不要输出；输出必须是合法 JSON，不要 Markdown 代码块。"
        )
        relation_user = f"""论文标题：{title}

已抽取实体：
{entity_lines}

原文（可能被截断）：
{markdown}

请只抽取实体之间的关系，输出 JSON：
{{
  "relations": [
    {{"sourceName": "源实体名", "sourceType": "PAPER", "targetName": "目标实体名", "targetType": "METHOD", "relationType": "STUDIES|USES_METHOD|USES_DATASET|CITES|AFFILIATED|AUTHORED", "confidence": 0.0-1.0, "evidence": "原文依据"}}
  ]
}}

要求：
1. 源/目标必须来自上面的实体列表，名称、类型必须与列表一致。
2. 论文使用方法/模型用 USES_METHOD，使用数据集用 USES_DATASET，研究主题用 STUDIES，引用其他论文用 CITES。
3. 每条关系必须有原文依据；关系最多 50 条，evidence 不超过 80 字。"""

        try:
            relation_result: ChatResult = self.llm.chat(
                messages=[
                    {"role": "system", "content": relation_system},
                    {"role": "user", "content": relation_user},
                ],
                temperature=0.1,
                max_tokens=3000,
            )
            relation_data = _json_object(relation_result.text)
            relations = self._normalize_relations(relation_data.get("relations"), entities)
            model_name = relation_result.model or model_name
            logger.info("知识抽取关系结果：relations=%d", len(relations))
        except (LLMError, ValueError, KeyError, TypeError) as exc:
            logger.warning("知识关系抽取失败，保留实体：%s", exc)
            relations = []
        except Exception as exc:  # noqa: BLE001
            logger.warning("知识关系抽取异常，保留实体：%s", exc)
            relations = []

        return KnowledgeResponse(
            entities=entities,
            relations=relations,
            modelName=model_name,
        )

    def _normalize_entities(self, raw: object, title: str) -> list[KnowledgeEntity]:
        entities: list[KnowledgeEntity] = []
        seen: set[str] = set()

        if isinstance(raw, list):
            for item in raw:
                if not isinstance(item, dict):
                    continue
                name = _clean(item.get("name"))
                entity_type = _clean(item.get("type")).upper()
                if not name or entity_type not in _ENTITY_TYPES:
                    continue
                key = name.lower() + "|" + entity_type
                if key in seen:
                    continue
                seen.add(key)
                entities.append(
                    KnowledgeEntity(
                        name=name[:255],
                        type=entity_type,
                        description=_clean(item.get("description"))[:500] or None,
                        confidence=_clamp(item.get("confidence")),
                        evidence=_clean(item.get("evidence"))[:500] or None,
                    )
                )
                if len(entities) >= _MAX_ENTITIES - 1:
                    break

        paper_key = title.lower() + "|PAPER"
        if paper_key not in seen:
            entities.insert(
                0,
                KnowledgeEntity(
                    name=title[:255],
                    type="PAPER",
                    description="论文",
                    confidence=1.0,
                    evidence="文献标题",
                ),
            )

        return entities[:_MAX_ENTITIES]

    def _normalize_relations(
        self,
        raw: object,
        entities: list[KnowledgeEntity],
    ) -> list[KnowledgeRelation]:
        if not isinstance(raw, list):
            return []

        entity_keys = {(entity.name.lower(), entity.type) for entity in entities}
        relations: list[KnowledgeRelation] = []
        seen: set[str] = set()

        for item in raw:
            if not isinstance(item, dict):
                continue
            source_name = _clean(item.get("sourceName"))
            target_name = _clean(item.get("targetName"))
            source_type = _clean(item.get("sourceType")).upper()
            target_type = _clean(item.get("targetType")).upper()
            relation_type = _clean(item.get("relationType")).upper()
            relation_type = _RELATION_ALIASES.get(relation_type, relation_type)

            if (
                not source_name
                or not target_name
                or source_type not in _ENTITY_TYPES
                or target_type not in _ENTITY_TYPES
                or relation_type not in _RELATION_TYPES
            ):
                continue
            if (source_name.lower(), source_type) not in entity_keys:
                continue
            if (target_name.lower(), target_type) not in entity_keys:
                continue

            key = "|".join(
                [source_name.lower(), source_type, target_name.lower(), target_type, relation_type]
            )
            if key in seen:
                continue
            seen.add(key)

            relations.append(
                KnowledgeRelation(
                    sourceName=source_name[:255],
                    sourceType=source_type,
                    targetName=target_name[:255],
                    targetType=target_type,
                    relationType=relation_type,
                    confidence=_clamp(item.get("confidence")),
                    evidence=_clean(item.get("evidence"))[:500] or None,
                )
            )
            if len(relations) >= _MAX_RELATIONS:
                break

        return relations
