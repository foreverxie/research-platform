"""文献结构化分析：有 LLM 走模型，无 Key 走启发式。"""

from __future__ import annotations

import json
import logging
import re
from collections import Counter

from fastapi import HTTPException

from .. import db
from ..config import Settings, get_settings
from ..schemas import AnalyzeRequest, AnalyzeResponse
from .llm import ChatResult, LLMError, LLMService
from .parser import resolve_storage_file

logger = logging.getLogger(__name__)

PLACEHOLDER = "原文未明确陈述"
LOCAL_MODEL = "local-fallback"

_BACKGROUND_KEYWORDS = (
    "背景", "问题", "挑战", "动机", "现状", "随着", "然而", "不足", "局限",
    "introduction", "background", "motivation", "challenge", "problem",
)
_METHOD_KEYWORDS = (
    "方法", "模型", "算法", "框架", "网络", "训练", "实验设计", "本文提出",
    "构建", "采用", "method", "approach", "model", "architecture", "training",
)
_RESULT_KEYWORDS = (
    "结果", "实验", "性能", "准确率", "召回率", "精确率", "指标", "提升",
    "对比", "优于", "results", "experiment", "evaluation", "accuracy",
    "performance", "improve",
)
_CONCLUSION_KEYWORDS = (
    "结论", "总结", "未来", "局限", "展望", "conclusion", "summary",
    "future work", "limitation",
)
_INNOVATION_KEYWORDS = (
    "创新", "贡献", "首次", "提出", "新颖", "改进", "novel", "contribution",
    "first", "propose", "introduce",
)

_CN_STOP_WORDS = {
    "本文", "我们", "作者", "通过", "进行", "以及", "一个", "可以", "这种",
    "由于", "因此", "但是", "如果", "其中", "对于", "不是", "没有", "这个",
    "这些", "研究", "实验", "结果", "方法", "模型", "数据", "问题", "提出",
    "使用", "基于", "显示", "表明", "具有", "能够", "提高", "不同", "相关",
    "主要", "目前", "已经", "需要", "方面", "过程", "分析", "文章", "论文",
    "文献", "内容", "工作", "系统", "结构", "性能", "效果", "影响", "应用",
    "技术", "设计", "实现", "重要", "关键", "目标", "意义", "贡献", "创新",
}
_CN_EDGE_CHARS = set("的了是在和与及对为中上下这那有被把将从到等就也都而及其之以于并且或但如若则因由使让给向自至")
_EN_STOP_WORDS = {
    "the", "and", "for", "with", "that", "this", "these", "those", "from",
    "into", "are", "was", "were", "been", "being", "have", "has", "had",
    "not", "but", "its", "our", "their", "his", "her", "they", "them",
    "you", "your", "can", "could", "would", "should", "may", "might",
    "will", "shall", "than", "then", "there", "here", "which", "who",
    "what", "when", "where", "why", "how", "also", "such", "more", "most",
    "some", "any", "all", "each", "other", "only", "about", "over",
    "under", "between", "within", "without", "using", "based", "paper",
    "study", "results", "method", "methods", "approach", "propose",
    "need", "one", "two", "new", "use", "used", "using", "may", "also",
    "mechanisms", "mechanism", "models", "model", "sequence", "sequences",
    "information", "different", "large", "small", "over", "more", "such",
}

# 轻量领域词典：用于弥补没有分词器时的中文关键词抽取，命中后仍按词频排序
_CN_TERM_DICT = (
    "检索增强生成", "卷积神经网络", "循环神经网络", "图神经网络", "注意力机制",
    "大语言模型", "语言模型", "预训练模型", "医学图像分割", "图像分割",
    "命名实体识别", "信息抽取", "知识图谱", "语义检索", "向量数据库",
    "推荐系统", "问答系统", "对话系统", "机器翻译", "目标检测", "文本分类",
    "强化学习", "迁移学习", "联邦学习", "深度学习", "机器学习", "神经网络",
    "自注意力", "多模态", "预训练", "微调", "注意力", "卷积", "循环",
    "编码器", "解码器", "特征提取", "模型压缩", "知识蒸馏", "对抗训练",
    "评价指标", "准确率", "召回率", "精确率", "鲁棒性", "可解释性",
    "幻觉", "数据集", "基准测试", "消融实验", "基线", "泛化能力",
    "语义理解", "文本生成", "图像识别", "分类", "回归", "聚类", "推理",
    "训练效率", "并行计算", "长距离依赖", "上下文", "词向量", "嵌入",
    "相似度", "排序", "检索", "生成", "模型", "算法", "框架", "任务",
    "性能", "效率", "准确", "误差", "损失函数", "优化", "梯度",
)

_REFERENCE_HEADING_RE = re.compile(
    r"(?im)^\s*#{0,6}\s*(?:references?|bibliography|参考文献)\s*$"
)
# 中文句号后无空格也要切；英文句号要求后面有空白，避免切开 28.4 这类小数
_SENTENCE_RE = re.compile(r"(?<=[。！？；])\s*|(?<=[.!?;])\s+")
_SECTION_TITLES = {
    "abstract", "introduction", "background", "related work", "motivation",
    "method", "methods", "methodology", "approach", "experiments", "experiment",
    "results", "discussion", "conclusion", "conclusions", "references",
    "acknowledgments", "appendix", "摘要", "引言", "绪论", "研究背景", "相关工作",
    "方法", "研究方法", "实验", "实验结果", "结果", "讨论", "结论", "总结",
    "参考文献", "致谢", "附录",
}
_NUMBERED_SECTION_RE = re.compile(r"^\d+(?:\.\d+){0,3}\s+[A-Za-z\u4e00-\u9fff]")


def _truncate(text: str, limit: int) -> str:
    text = (text or "").strip()
    if len(text) <= limit:
        return text
    cut = text[:limit]
    last_stop = max(cut.rfind("。"), cut.rfind("！"), cut.rfind("？"), cut.rfind("."), cut.rfind(";"))
    if last_stop > limit * 0.6:
        cut = cut[: last_stop + 1]
    return cut.strip() + "…"


def _clean_markdown(text: str) -> str:
    text = text or ""
    text = re.sub(r"<!--.*?-->", " ", text, flags=re.S)
    text = re.sub(r"```.*?```", " ", text, flags=re.S)
    text = re.sub(r"!\[[^\]]*]\([^)]*\)", " ", text)
    text = re.sub(r"\[([^\]]+)]\([^)]*\)", r"\1", text)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"[*_`~]{1,3}", "", text)

    match = _REFERENCE_HEADING_RE.search(text)
    if match:
        text = text[: match.start()]

    text = re.sub(r"[ \t\u00a0]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def _is_cjk(char: str) -> bool:
    return bool(char) and "\u3400" <= char <= "\u9fff"


def _join_lines(lines: list[str]) -> str:
    output = ""
    for line in lines:
        line = line.strip()
        if not line:
            continue
        if not output:
            output = line
        elif _is_cjk(output[-1]) and _is_cjk(line[0]):
            output += line
        else:
            output += " " + line
    return output


def _is_bare_heading(line: str) -> bool:
    stripped = (line or "").strip()
    if stripped.startswith("#"):
        return True
    text = stripped.lstrip("#").strip()
    if not text:
        return False
    lowered = text.lower().strip(" :：.。、")
    if lowered in _SECTION_TITLES:
        return True
    if _NUMBERED_SECTION_RE.match(text) and len(text) <= 80:
        return not text.endswith(("。", ".", "，", ",", "；", ";", "！", "!", "？", "?"))
    return False


def _split_paragraphs(text: str) -> list[str]:
    cleaned = _clean_markdown(text)
    if not cleaned:
        return []

    raw_parts = [part.strip() for part in re.split(r"\n\s*\n", cleaned) if part.strip()]
    paragraphs: list[str] = []
    for part in raw_parts:
        # 同一段里可能夹着 “Abstract”“1 Introduction” 这类无 Markdown 标记的标题行
        lines = [line.strip() for line in part.splitlines() if line.strip()]
        kept = [line for line in lines if not _is_bare_heading(line)]
        if not kept:
            continue
        part = _join_lines(kept).strip()
        if not part:
            continue
        if len(part) < 25 and not re.search(r"[。！？.!?]", part):
            continue
        if re.match(r"^[\|\-\s:]+$", part):
            continue
        if len(part) > 1800:
            sentences = [s for s in _SENTENCE_RE.split(part) if s.strip()]
            group = ""
            for sentence in sentences:
                if len(group) + len(sentence) > 900 and group:
                    paragraphs.append(group.strip())
                    group = sentence
                else:
                    group += sentence
            if group.strip():
                paragraphs.append(group.strip())
        else:
            paragraphs.append(part)

    if len(paragraphs) <= 1 and cleaned:
        sentences = [s for s in _SENTENCE_RE.split(cleaned) if s.strip()]
        paragraphs = []
        group = ""
        for sentence in sentences:
            if len(group) + len(sentence) > 700 and group:
                paragraphs.append(group.strip())
                group = sentence
            else:
                group += sentence
        if group.strip():
            paragraphs.append(group.strip())

    return paragraphs


def _pick(paragraphs: list[str], keywords: tuple[str, ...], limit: int = 2, max_len: int = 500) -> str:
    lowered = [(p, p.lower()) for p in paragraphs]
    hits: list[str] = []
    for paragraph, low in lowered:
        if any(keyword.lower() in low for keyword in keywords):
            hits.append(paragraph)
            if len(hits) >= limit:
                break
    if not hits:
        return ""
    return _truncate(" ".join(hits), max_len)


def _sentences(text: str) -> list[str]:
    return [part.strip() for part in _SENTENCE_RE.split(text or "") if part.strip()]


def _innovation_points(text: str, paragraphs: list[str]) -> list[str]:
    candidates: list[str] = []
    for sentence in _sentences(text):
        sentence = re.sub(r"\s+", " ", sentence).strip()
        if not (15 <= len(sentence) <= 320):
            continue
        low = sentence.lower()
        if any(keyword.lower() in low for keyword in _INNOVATION_KEYWORDS):
            candidates.append(sentence)

    points: list[str] = []
    seen: set[str] = set()
    for sentence in candidates:
        key = re.sub(r"\s+", "", sentence)[:60]
        if key in seen:
            continue
        seen.add(key)
        points.append(_truncate(sentence, 220))
        if len(points) >= 3:
            break
    return points


def _extract_keywords(text: str, title: str, limit: int = 8) -> list[str]:
    source = f"{title}\n{(text or '')[:8000]}"
    title_lower = (title or "").lower()
    selected: list[str] = []
    candidates: dict[str, int] = {}  # 展示文本 -> 分数

    # 1) 领域词典命中 + 英文术语，统一按词频/标题加权排序
    for term in _CN_TERM_DICT:
        count = source.count(term)
        if count <= 0:
            continue
        candidates[term] = count + (2 if term in title else 0)

    for match in re.finditer(r"[A-Za-z][A-Za-z0-9\-]{2,}", source):
        word = match.group(0)
        low = word.lower()
        if low in _EN_STOP_WORDS or len(low) < 3:
            continue
        candidates[word] = candidates.get(word, 0) + (3 if low in title_lower else 1)

    for term, _score in sorted(
        candidates.items(), key=lambda item: (item[1], len(item[0])), reverse=True
    ):
        if any(
            term.lower() == existing.lower()
            or term in existing
            or existing in term
            for existing in selected
        ):
            continue
        selected.append(term)
        if len(selected) >= limit:
            break

    # 2) 兜底：高频中文二元组
    if len(selected) < limit:
        bigrams: Counter[str] = Counter()
        for run in re.findall(r"[\u4e00-\u9fff]{2,}", source):
            for index in range(len(run) - 1):
                gram = run[index : index + 2]
                if gram in _CN_STOP_WORDS:
                    continue
                if gram[0] in _CN_EDGE_CHARS or gram[-1] in _CN_EDGE_CHARS:
                    continue
                bigrams[gram] += 1
        for gram, count in bigrams.most_common():
            if count < 2:
                break
            if any(gram in existing or existing in gram for existing in selected):
                continue
            selected.append(gram)
            if len(selected) >= limit:
                break

    if not selected:
        selected = [
            word
            for word in re.findall(r"[A-Za-z][A-Za-z0-9\-]{2,}", title or "")
            if word.lower() not in _EN_STOP_WORDS
        ][:limit]
    if not selected and title:
        selected = [title[:30]]
    return selected[:limit]


def _json_candidates(content: str) -> dict:
    text = (content or "").strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.I)
        text = re.sub(r"\s*```$", "", text)
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("模型输出中没有 JSON 对象")
    return json.loads(text[start : end + 1])


def _as_text(value: object, fallback: str = PLACEHOLDER) -> str:
    if isinstance(value, str):
        text = value.strip()
    elif isinstance(value, list):
        text = "；".join(str(item).strip() for item in value if str(item).strip())
    elif value is None:
        text = ""
    else:
        text = str(value).strip()
    return text or fallback


def _as_list(value: object) -> list[str]:
    if isinstance(value, list):
        items = [str(item).strip() for item in value if str(item).strip()]
    elif isinstance(value, str):
        items = [item.strip() for item in re.split(r"[\n;；]+", value) if item.strip()]
    else:
        items = []
    return items


class Analyzer:
    def __init__(self, llm: LLMService, settings: Settings | None = None) -> None:
        self.llm = llm
        self.settings = settings or get_settings()

    # ------------------------------------------------------------------ 入口
    def analyze(self, request: AnalyzeRequest) -> AnalyzeResponse:
        title, abstract, _ = self._resolve_document_meta(request)
        text, source = self._load_text(request)
        if not text:
            text = abstract

        if not text.strip():
            logger.warning("文献 %s 没有可分析的正文/摘要", request.documentId)
            return self._empty_result(title)

        llm_text = text[: self.settings.analyze_max_chars]
        if self.llm.available:
            try:
                result = self._llm_analyze(
                    request=request,
                    title=title,
                    abstract=abstract,
                    text=llm_text,
                )
                logger.info(
                    "文献分析完成（LLM）：documentId=%s, model=%s, source=%s",
                    request.documentId,
                    result.modelName,
                    source,
                )
                return result
            except (LLMError, ValueError, KeyError, TypeError) as exc:
                logger.warning("LLM 分析失败，改用本地启发式：%s", exc)
            except Exception as exc:  # noqa: BLE001
                logger.warning("LLM 分析异常，改用本地启发式：%s", exc)

        result = self._heuristic_analyze(request=request, title=title, abstract=abstract, text=text)
        logger.info(
            "文献分析完成（本地）：documentId=%s, source=%s, chars=%d",
            request.documentId,
            source,
            len(text),
        )
        return result

    # ------------------------------------------------------------------ 数据准备
    def _resolve_document_meta(self, request: AnalyzeRequest) -> tuple[str, str, dict | None]:
        meta: dict | None = None
        if request.documentId:
            try:
                meta = db.get_document_meta(request.documentId)
            except Exception as exc:  # noqa: BLE001
                logger.warning("读取文献元信息失败：%s", exc)

        title = (request.title or "").strip()
        abstract = (request.abstractText or "").strip()
        if meta:
            title = title or str(meta.get("title") or "").strip()
            abstract = abstract or str(meta.get("abstract") or "").strip()
        return title, abstract, meta

    def _load_text(self, request: AnalyzeRequest) -> tuple[str, str]:
        relative_path = (request.markdownPath or "").strip()
        source = "markdownPath"
        if not relative_path and request.documentId:
            try:
                relative_path = db.resolve_markdown_path(request.documentId) or ""
                source = "mysql"
            except Exception as exc:  # noqa: BLE001
                logger.warning("从 MySQL 反查 Markdown 失败：%s", exc)
                relative_path = ""
                source = "none"

        if not relative_path:
            return "", "none"

        try:
            path = resolve_storage_file(relative_path)
            text = path.read_text(encoding="utf-8", errors="replace")
            return text, f"{source}:{relative_path}"
        except HTTPException as exc:
            logger.warning("读取 Markdown 失败：path=%s, reason=%s", relative_path, exc.detail)
        except OSError as exc:
            logger.warning("读取 Markdown 失败：path=%s, reason=%s", relative_path, exc)
        return "", "none"

    # ------------------------------------------------------------------ 本地
    def _heuristic_analyze(
        self,
        request: AnalyzeRequest,
        title: str,
        abstract: str,
        text: str,
    ) -> AnalyzeResponse:
        if len(text) > 60000:
            text = text[:40000] + "\n\n" + text[-20000:]
        paragraphs = _split_paragraphs(text)
        if not paragraphs and abstract:
            paragraphs = _split_paragraphs(abstract)

        title_clean = title.strip()
        summary_paragraphs = [p for p in paragraphs if p.strip() != title_clean][:2]
        summary = _truncate(" ".join(summary_paragraphs), 600)
        if not summary:
            summary = _truncate(abstract, 600)
        if not summary:
            summary = f"文献《{title_clean or '未命名文献'}》的正文内容较少，暂无法生成有效摘要。"

        background = _pick(paragraphs, _BACKGROUND_KEYWORDS)
        method = _pick(paragraphs, _METHOD_KEYWORDS)
        results = _pick(paragraphs, _RESULT_KEYWORDS)
        conclusion = _pick(paragraphs, _CONCLUSION_KEYWORDS)

        if not background:
            background = _truncate(abstract, 500)

        innovation = _innovation_points(text, paragraphs)
        if not innovation:
            innovation = [PLACEHOLDER]

        keywords = _extract_keywords(text, title_clean)
        if not keywords:
            keywords = [PLACEHOLDER]

        return AnalyzeResponse(
            summary=summary or PLACEHOLDER,
            researchBackground=background or PLACEHOLDER,
            researchMethod=method or PLACEHOLDER,
            innovationPoints=innovation,
            experimentalResults=results or PLACEHOLDER,
            conclusion=conclusion or PLACEHOLDER,
            keywords=keywords,
            modelName=LOCAL_MODEL,
            promptTokens=0,
            completionTokens=0,
        )

    def _empty_result(self, title: str) -> AnalyzeResponse:
        title = title or "未命名文献"
        keywords = _extract_keywords(title, title)
        return AnalyzeResponse(
            summary=f"未能获取《{title}》的正文或摘要，无法进行内容归纳，请先完成解析或补充摘要。",
            researchBackground=PLACEHOLDER,
            researchMethod=PLACEHOLDER,
            innovationPoints=[PLACEHOLDER],
            experimentalResults=PLACEHOLDER,
            conclusion=PLACEHOLDER,
            keywords=keywords or [PLACEHOLDER],
            modelName=LOCAL_MODEL,
            promptTokens=0,
            completionTokens=0,
        )

    # ------------------------------------------------------------------ LLM
    def _llm_analyze(
        self,
        request: AnalyzeRequest,
        title: str,
        abstract: str,
        text: str,
    ) -> AnalyzeResponse:
        analysis_type = (request.analysisType or "FULL_ANALYSIS").strip()
        system = (
            "你是一名严谨的科研文献分析助手。只能依据用户提供的原文作答，"
            "不得编造原文没有的信息。输出必须是合法的 JSON 对象，不要使用 Markdown 代码块。"
        )
        user = f"""分析类型：{analysis_type}
文献标题：{title or "未提供"}
文献摘要：{abstract or "未提供"}

原文（可能因长度被截断）：
{text}

请输出 JSON，且必须包含以下字段：
{{
  "summary": "研究摘要",
  "researchBackground": "研究背景与问题",
  "researchMethod": "研究方法",
  "innovationPoints": ["创新点1", "创新点2"],
  "experimentalResults": "实验结果",
  "conclusion": "研究结论",
  "keywords": ["关键词1", "关键词2"]
}}

要求：
1. 每个文本字段 1-4 句，直接依据原文；原文没有明确说明时统一写“原文未明确陈述”。
2. innovationPoints 给 1-3 条，keywords 给 3-8 个。
3. 全部使用中文（专有名词可保留英文）。"""

        result: ChatResult = self.llm.chat(
            messages=[
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ]
        )
        data = _json_candidates(result.text)

        summary = _as_text(data.get("summary"))
        background = _as_text(data.get("researchBackground"))
        method = _as_text(data.get("researchMethod"))
        results = _as_text(data.get("experimentalResults"))
        conclusion = _as_text(data.get("conclusion"))

        innovations = _as_list(data.get("innovationPoints"))
        if not innovations:
            innovations = _innovation_points(text, _split_paragraphs(text)) or [PLACEHOLDER]

        keywords = _as_list(data.get("keywords"))
        if not keywords:
            keywords = _extract_keywords(text, title) or [PLACEHOLDER]

        return AnalyzeResponse(
            summary=summary,
            researchBackground=background,
            researchMethod=method,
            innovationPoints=innovations,
            experimentalResults=results,
            conclusion=conclusion,
            keywords=keywords,
            modelName=result.model or self.llm.model_name,
            promptTokens=result.prompt_tokens,
            completionTokens=result.completion_tokens,
        )
