"""文献元数据自动识别：PDF 元信息 + 首页正文，有 LLM 走模型，无 Key 走规则。"""

from __future__ import annotations

import json
import logging
import re
from pathlib import Path

import pymupdf

from ..config import Settings, get_settings
from ..schemas import MetadataRequest, MetadataResponse
from .llm import ChatResult, LLMError, LLMService
from .parser import resolve_storage_file

logger = logging.getLogger(__name__)

_LOCAL_MODEL = "local-fallback"
_MAX_TEXT = 12000

_DOI_RE = re.compile(r"\b10\.\d{4,9}/[-._;()/:A-Za-z0-9]+\b")
_YEAR_RE = re.compile(r"\b(19|20)\d{2}\b")
_ABSTRACT_RE = re.compile(
    r"(?is)\babstract\b\s*[:：]?\s*(.+?)(?=\n\s*(?:#{1,3}\s*)?(?:1\s+)?introduction\b|\n\s*(?:keywords?|关键词)\b|$)"
)
_CN_ABSTRACT_RE = re.compile(
    r"(?is)摘\s*要\s*[:：]?\s*(.+?)(?=\n\s*(?:关键词|1\s*引言|引\s*言|绪\s*论)\b|$)"
)
_KEYWORDS_RE = re.compile(r"(?im)^\s*(?:keywords?|关键词)\s*[:：]?\s*(.+)$")
_HEADING_RE = re.compile(r"^#{1,3}\s+(.+)$", re.MULTILINE)
_EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
_VENUE_RE = re.compile(
    r"(?im)^[^\n]{0,80}(?:proceedings of|conference on|symposium on|workshop on|"
    r"neurips|nips|cvpr|icml|iclr|aaai|ijcai|acl|emnlp)[^\n]{0,120}$"
)

_CONFERENCE_HINTS = (
    "conference", "proceedings", "neurips", "nips", "icml", "iclr", "cvpr",
    "iccv", "eccv", "aaai", "ijcai", "acl", "emnlp", "kdd", "sigir", "会议",
)
_THESIS_HINTS = ("thesis", "dissertation", "学位论文", "硕士论文", "博士论文")
_PREPRINT_HINTS = ("arxiv", "preprint", "bioRxiv", "medRxiv", "预印本")


def _clean(value: object) -> str:
    return re.sub(r"\s+", " ", str(value or "")).strip()


def _split_people(value: str) -> list[str]:
    text = _clean(value)
    if not text:
        return []
    if ";" in text or "；" in text:
        parts = re.split(r"[;；]", text)
    elif " and " in text.lower():
        parts = re.split(r"\s+and\s+", text, flags=re.I)
    elif "、" in text:
        parts = text.split("、")
    else:
        parts = [text]
    return [part.strip(" ,，") for part in parts if part.strip(" ,，")]


def _extract_abstract(text: str) -> str:
    for pattern in (_ABSTRACT_RE, _CN_ABSTRACT_RE):
        match = pattern.search(text)
        if match:
            abstract = _clean(match.group(1))
            if len(abstract) >= 40:
                return abstract[:1800]
    return ""


def _extract_keywords(text: str) -> list[str]:
    match = _KEYWORDS_RE.search(text)
    if not match:
        return []
    raw = match.group(1)
    raw = re.split(r"\n", raw, maxsplit=1)[0]
    parts = re.split(r"[,，;；、]", raw)
    return [part.strip(" .。") for part in parts if 1 < len(part.strip()) <= 40][:8]


def _extract_authors_from_text(text: str) -> list[str]:
    """从首页前几行里找作者行，主要作为 PDF 元信息缺失时的兜底。"""
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    for line in lines[:12]:
        lowered = line.lower()
        if lowered.startswith(("#", "abstract", "摘要", "keywords", "doi", "http")):
            continue
        if _EMAIL_RE.search(line):
            continue
        if 5 <= len(line) <= 200 and re.search(r"[A-Za-z\u4e00-\u9fa5]", line):
            if re.search(r"\b(University|Institute|Department|Laboratory|大学|学院|研究所)\b", line, re.I):
                continue
            names = _split_people(line)
            if 1 <= len(names) <= 12 and all(len(name) <= 60 for name in names):
                return names
    return []


def _detect_type(text: str) -> str:
    lowered = (text or "").lower()
    if any(hint.lower() in lowered for hint in _THESIS_HINTS):
        return "THESIS"
    # 有正式会议/期刊录用信息时优先按会议/期刊判断，避免被文末 arXiv 标识带偏
    if any(hint.lower() in lowered for hint in _CONFERENCE_HINTS):
        return "CONFERENCE"
    if any(hint.lower() in lowered for hint in _PREPRINT_HINTS):
        return "PREPRINT"
    if re.search(r"\b(book|monograph|专著|教材)\b", lowered):
        return "BOOK"
    return "JOURNAL"


def _detect_language(text: str) -> str:
    if not text:
        return "英文"
    chinese = len(re.findall(r"[\u4e00-\u9fa5]", text))
    return "中文" if chinese >= max(20, len(text) * 0.08) else "英文"


def _pretty_title(title: str, file_name: str | None) -> str:
    value = _clean(title)
    stem = Path(file_name or "").stem
    if not value:
        return stem
    lowered = value.lower()
    if lowered in {"untitled", "no title", "无标题", "document"}:
        return stem
    if stem and lowered == stem.lower():
        return stem
    return value


class MetadataExtractor:
    def __init__(self, llm: LLMService, settings: Settings | None = None) -> None:
        self.llm = llm
        self.settings = settings or get_settings()

    def extract(self, request: MetadataRequest) -> MetadataResponse:
        markdown = (request.markdown or "").strip()
        pdf_path: Path | None = None
        pdf_meta: dict[str, str] = {}

        if request.filePath:
            try:
                pdf_path = resolve_storage_file(request.filePath)
                pdf_meta = self._pdf_metadata(pdf_path)
            except Exception as exc:  # noqa: BLE001
                logger.warning("读取 PDF 元信息失败：%s", exc)

        if not markdown and pdf_path is not None:
            markdown = self._first_pages_text(pdf_path)

        heuristic = self._heuristic(
            markdown=markdown,
            pdf_meta=pdf_meta,
            file_name=request.fileName,
            current_title=request.title,
        )

        if self.llm.available:
            try:
                result = self._llm_extract(markdown, pdf_meta, heuristic, request.fileName)
                logger.info(
                    "元数据识别完成（LLM）：documentId=%s, model=%s",
                    request.documentId,
                    result.modelName,
                )
                return result
            except (LLMError, ValueError, KeyError, TypeError) as exc:
                logger.warning("元数据 LLM 识别失败，改用规则：%s", exc)
            except Exception as exc:  # noqa: BLE001
                logger.warning("元数据 LLM 识别异常，改用规则：%s", exc)

        return heuristic

    # ------------------------------------------------------------------ PDF
    def _pdf_metadata(self, path: Path) -> dict[str, str]:
        document = pymupdf.open(path)
        try:
            meta = document.metadata or {}
            return {
                str(key).lower(): _clean(value)
                for key, value in meta.items()
                if value is not None
            }
        finally:
            document.close()

    def _first_pages_text(self, path: Path) -> str:
        document = pymupdf.open(path)
        try:
            texts = []
            for page_no, page in enumerate(document, start=1):
                if page_no > 2:
                    break
                texts.append(page.get_text("text", sort=True))
            return "\n\n".join(texts)[:_MAX_TEXT]
        finally:
            document.close()

    # -------------------------------------------------------------- heuristic
    def _heuristic(
        self,
        markdown: str,
        pdf_meta: dict[str, str],
        file_name: str | None,
        current_title: str | None,
    ) -> MetadataResponse:
        text = markdown or ""
        heading_match = _HEADING_RE.search(text)
        heading = _clean(heading_match.group(1)) if heading_match else ""

        pdf_title = pdf_meta.get("title", "")
        title = _pretty_title(pdf_title or heading or current_title or "", file_name)

        author_text = pdf_meta.get("author", "") or pdf_meta.get("creator", "")
        authors = _split_people(author_text) or _extract_authors_from_text(text)

        doi_match = _DOI_RE.search(text)

        # 年份优先取 PDF 创建日期；正文里的 WMT 2014 等实验年份不能当发表年
        pdf_date = pdf_meta.get("creationdate", "") or pdf_meta.get("moddate", "")
        pdf_year_match = _YEAR_RE.search(pdf_date)
        year_match = pdf_year_match or _YEAR_RE.search(text[:6000])

        keywords = _extract_keywords(text)
        abstract = _extract_abstract(text)

        journal = pdf_meta.get("subject", "")
        if not journal:
            venue_match = _VENUE_RE.search(text[:8000])
            journal = _clean(venue_match.group(0)) if venue_match else ""

        if not abstract:
            # 没有显式 Abstract 时，取第一段较长的正文
            paragraphs = [p.strip() for p in re.split(r"\n\s*\n", text) if len(p.strip()) >= 120]
            abstract = paragraphs[0][:1200] if paragraphs else ""

        return MetadataResponse(
            title=title or "未命名文献",
            authors=authors[:12],
            journal=journal or None,
            doi=doi_match.group(0).rstrip(".,;)") if doi_match else None,
            publicationYear=int(year_match.group(0)) if year_match else None,
            abstractText=abstract or None,
            keywords=keywords,
            documentType=_detect_type(text),
            language=_detect_language(text),
            modelName=_LOCAL_MODEL,
        )

    # -------------------------------------------------------------------- LLM
    def _llm_extract(
        self,
        markdown: str,
        pdf_meta: dict[str, str],
        heuristic: MetadataResponse,
        file_name: str | None,
    ) -> MetadataResponse:
        system = (
            "你是科研文献元数据抽取助手。只能依据给出的 PDF 元信息和原文，"
            "无法确定的字段输出 null，禁止编造。只输出合法 JSON，不要 Markdown 代码块。"
        )
        user = f"""文件名：{file_name or "未提供"}
PDF 元信息：
- title: {pdf_meta.get("title") or "未提供"}
- author: {pdf_meta.get("author") or "未提供"}
- subject: {pdf_meta.get("subject") or "未提供"}
- keywords: {pdf_meta.get("keywords") or "未提供"}
- creationDate: {pdf_meta.get("creationdate") or "未提供"}

规则兜底结果（仅供参考，可纠正）：
{json.dumps(heuristic.model_dump(), ensure_ascii=False)}

原文开头：
{markdown[:_MAX_TEXT]}

请输出 JSON：
{{
  "title": "论文标题",
  "authors": ["作者1", "作者2"],
  "journal": "期刊或会议名称，没有则 null",
  "doi": "DOI，没有则 null",
  "publicationYear": 2024,
  "abstractText": "摘要，没有则 null",
  "keywords": ["关键词1", "关键词2"],
  "documentType": "JOURNAL|CONFERENCE|THESIS|PREPRINT|BOOK|OTHER",
  "language": "中文|英文"
}}

要求：
1. 标题不要用文件名，除非原文确实没有标题。
2. 作者只保留人名，去掉邮箱、单位、上标数字。
3. 摘要优先用原文 Abstract/摘要；没有就返回 null，不要自己总结。
4. 关键词 3-8 个；没有明确关键词时给 3-6 个主题词。
5. 年份只填论文正式发表年份，无法确定返回 null；不要把 WMT 2014 这类实验/数据集年份当发表年。
6. journal：有 Proceedings/Conference/期刊名就填；documentType 有正式录用信息时优先 CONFERENCE/JOURNAL，不要因为出现 arXiv 就判 PREPRINT。"""

        result: ChatResult = self.llm.chat(
            messages=[
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            temperature=0.1,
            max_tokens=1200,
        )
        data = self._parse_json(result.text)

        title = _pretty_title(data.get("title") or heuristic.title, file_name)
        authors = self._as_list(data.get("authors")) or heuristic.authors
        keywords = self._as_list(data.get("keywords")) or heuristic.keywords

        # PDF 创建日期里的年份通常就是论文年份；正文的 WMT 2014 不能当发表年
        pdf_year_match = _YEAR_RE.search(
            pdf_meta.get("creationdate", "") or pdf_meta.get("moddate", "")
        )
        year = data.get("publicationYear")
        try:
            year_value = int(year) if year not in (None, "", "null") else heuristic.publicationYear
        except (TypeError, ValueError):
            year_value = heuristic.publicationYear
        if pdf_year_match:
            year_value = int(pdf_year_match.group(0))

        document_type = _clean(data.get("documentType")).upper()
        if document_type not in {"JOURNAL", "CONFERENCE", "THESIS", "PREPRINT", "BOOK", "OTHER"}:
            document_type = heuristic.documentType

        language = _clean(data.get("language")) or heuristic.language

        return MetadataResponse(
            title=title or heuristic.title,
            authors=authors[:12],
            journal=_clean(data.get("journal")) or heuristic.journal,
            doi=_clean(data.get("doi")) or heuristic.doi,
            publicationYear=year_value,
            abstractText=_clean(data.get("abstractText")) or heuristic.abstractText,
            keywords=keywords[:8],
            documentType=document_type,
            language=language,
            modelName=result.model or self.llm.model_name,
        )

    @staticmethod
    def _parse_json(content: str) -> dict:
        text = (content or "").strip()
        if text.startswith("```"):
            text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.I)
            text = re.sub(r"\s*```$", "", text)
        start = text.find("{")
        end = text.rfind("}")
        if start < 0 or end <= start:
            raise ValueError("模型输出中没有 JSON 对象")
        data = json.loads(text[start : end + 1])
        if not isinstance(data, dict):
            raise ValueError("模型输出不是 JSON 对象")
        return data

    @staticmethod
    def _as_list(value: object) -> list[str]:
        if isinstance(value, list):
            return [_clean(item) for item in value if _clean(item)]
        if isinstance(value, str):
            return [_clean(item) for item in re.split(r"[,，;；、]", value) if _clean(item)]
        return []
