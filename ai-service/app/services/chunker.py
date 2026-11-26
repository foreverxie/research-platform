"""文本分块。

目标：每块约 600–900 字符，块间重叠 100 字符；``section`` 取最近标题，
没有标题时退化为 ``Page N``。
"""

from __future__ import annotations

import logging
import math
import re
from dataclasses import dataclass

logger = logging.getLogger(__name__)

_CJK_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[。！？；])\s*|(?<=[.!?;])\s+")
_WHITESPACE_RE = re.compile(r"[ \t\u00a0]+")


@dataclass(slots=True)
class TextBlock:
    """解析器输出的段落/标题。"""

    text: str
    page: int = 1
    heading: bool = False


@dataclass(slots=True)
class Chunk:
    section: str
    page_start: int
    page_end: int
    content: str
    token_count: int


def estimate_tokens(text: str) -> int:
    """粗略估算 token 数：中文按 1 字 1 token，其他按 4 字符 1 token。"""
    text = text or ""
    cjk = len(_CJK_RE.findall(text))
    non_cjk = max(0, len(text) - cjk)
    return max(1, cjk + math.ceil(non_cjk / 4))


def clean_text(text: str) -> str:
    if not text:
        return ""
    text = text.replace("\x00", " ").replace("\u200b", "")
    text = _WHITESPACE_RE.sub(" ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def split_long_text(text: str, max_chars: int) -> list[str]:
    """把超长段落按句子切成不超过 max_chars 的片段。"""
    text = text.strip()
    if not text:
        return []
    if len(text) <= max_chars:
        return [text]

    sentences = [s for s in _SENTENCE_SPLIT_RE.split(text) if s]
    pieces: list[str] = []
    current = ""

    for sentence in sentences:
        if len(sentence) > max_chars:
            if current:
                pieces.append(current)
                current = ""
            for start in range(0, len(sentence), max_chars):
                pieces.append(sentence[start : start + max_chars])
            continue

        if not current:
            current = sentence
        elif len(current) + len(sentence) <= max_chars:
            current += sentence
        else:
            pieces.append(current)
            current = sentence

    if current:
        pieces.append(current)
    return [p.strip() for p in pieces if p.strip()]


def build_chunks(
    blocks: list[TextBlock],
    min_chars: int = 600,
    max_chars: int = 900,
    overlap: int = 100,
    section_flush_min: int = 350,
) -> list[Chunk]:
    """把段落块合并为带章节、页码与重叠的文本块。"""
    units: list[tuple[str, int, str]] = []
    current_section: str | None = None

    for block in blocks:
        text = clean_text(block.text)
        if not text:
            continue

        page = block.page if block.page and block.page > 0 else 1
        if block.heading:
            # 标题不作为正文，只更新当前章节
            current_section = text.lstrip("#").strip() or current_section
            continue

        section = current_section or f"Page {page}"
        for piece in split_long_text(text, max_chars=max_chars):
            units.append((section, page, piece))

    if not units:
        return []

    chunks: list[Chunk] = []
    buffer: list[str] = []
    buffer_len = 0
    buffer_new_chars = 0
    buffer_section = ""
    buffer_section_chars: dict[str, int] = {}
    buffer_start = 0
    buffer_end = 0
    overlap_prefix = ""

    def flush(keep_overlap: bool = True) -> None:
        nonlocal buffer, buffer_len, buffer_new_chars
        nonlocal buffer_section, buffer_section_chars, buffer_start, buffer_end, overlap_prefix

        if buffer and buffer_new_chars > 0:
            content = "\n\n".join(buffer).strip()
            if content:
                # 一个块可能跨过多个短章节，取贡献字符最多的章节作为 section
                if buffer_section_chars:
                    section = max(buffer_section_chars.items(), key=lambda item: item[1])[0]
                else:
                    section = buffer_section or f"Page {buffer_start or 1}"
                chunks.append(
                    Chunk(
                        section=section,
                        page_start=buffer_start or 1,
                        page_end=buffer_end or buffer_start or 1,
                        content=content,
                        token_count=estimate_tokens(content),
                    )
                )
                if keep_overlap and overlap > 0 and len(content) > overlap:
                    overlap_prefix = content[-overlap:]
                else:
                    overlap_prefix = ""
        else:
            overlap_prefix = ""

        buffer = []
        buffer_len = 0
        buffer_new_chars = 0
        buffer_section_chars = {}

    for section, page, piece in units:
        # 章节变化时，如果当前内容已经足够多就先收口；过短的章节继续合并，
        # 避免把几百字的短文切成大量几十字的碎块。
        if buffer and section != buffer_section and buffer_len >= section_flush_min:
            flush()

        if not buffer:
            buffer_section = section
            buffer_start = page
            buffer_end = page
            if overlap_prefix:
                buffer.append(overlap_prefix)
                buffer_len = len(overlap_prefix)

        addition = (2 if buffer else 0) + len(piece)
        if buffer and buffer_len + addition > max_chars:
            flush()
            buffer_section = section
            buffer_start = page
            buffer_end = page
            if overlap_prefix:
                buffer.append(overlap_prefix)
                buffer_len = len(overlap_prefix)
                addition = 2 + len(piece)

        # 单段本身就接近 max_chars 时，不再叠加重叠前缀，避免超出 900 字符
        if (
            buffer
            and len(buffer) == 1
            and overlap_prefix
            and buffer[0] == overlap_prefix
            and buffer_len + addition > max_chars
        ):
            buffer = []
            buffer_len = 0
            buffer_new_chars = 0
            addition = len(piece)

        buffer.append(piece)
        buffer_len += addition
        buffer_new_chars += len(piece)
        buffer_end = max(buffer_end, page)
        buffer_section_chars[section] = buffer_section_chars.get(section, 0) + len(piece)

        if buffer_len >= min_chars:
            flush()

    flush(keep_overlap=False)

    # 末尾不足 200 字的小块尽量并入前一块，减少碎片
    while len(chunks) >= 2 and len(chunks[-1].content) < 200:
        previous = chunks[-2]
        last = chunks[-1]
        body = last.content
        if overlap > 0:
            tail = previous.content[-overlap:]
            if tail and body.startswith(tail):
                body = body[len(tail) :].lstrip()
        merged = previous.content + ("\n\n" + body if body else "")
        if len(merged) > max_chars:
            break
        chunks[-2] = Chunk(
            section=previous.section,
            page_start=previous.page_start,
            page_end=max(previous.page_end, last.page_end),
            content=merged,
            token_count=estimate_tokens(merged),
        )
        chunks.pop()

    logger.debug("分块完成：blocks=%d, chunks=%d", len(blocks), len(chunks))
    return chunks
