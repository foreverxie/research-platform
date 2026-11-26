"""PDF 解析：MinerU 可选 + PyMuPDF 兜底，生成 Markdown / 页级 JSON / 分块。"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

import pymupdf
from fastapi import HTTPException

from ..config import get_settings
from .chunker import Chunk, TextBlock, build_chunks, estimate_tokens

logger = logging.getLogger(__name__)

_HEADING_KEYWORDS = {
    "abstract",
    "introduction",
    "related work",
    "background",
    "motivation",
    "method",
    "methods",
    "methodology",
    "approach",
    "materials and methods",
    "experiments",
    "experiment",
    "experimental results",
    "results",
    "evaluation",
    "discussion",
    "conclusion",
    "conclusions",
    "future work",
    "references",
    "acknowledgments",
    "acknowledgements",
    "appendix",
    "摘要",
    "关键词",
    "引言",
    "绪论",
    "研究背景",
    "相关工作",
    "方法",
    "研究方法",
    "材料与方法",
    "实验",
    "实验设计",
    "实验结果",
    "结果",
    "结果与分析",
    "讨论",
    "结论",
    "总结",
    "未来工作",
    "参考文献",
    "致谢",
    "附录",
}

_PAGE_NO_RE = re.compile(r"^(?:page\s*)?\d{1,4}(?:\s*(?:/|of)\s*\d{1,4})?$", re.IGNORECASE)
_NUMBERED_HEADING_RE = re.compile(r"^\d+(?:\.\d+){0,3}[\s、.．]+[^\d\s]")
_CHINESE_CHAPTER_RE = re.compile(r"^第[0-9一二三四五六七八九十百零〇]+[章节部分篇]")
_CHINESE_NUMBER_RE = re.compile(r"^[一二三四五六七八九十]+[、.．]\s*\S")
_UPPER_HEADING_RE = re.compile(r"^[A-Z][A-Z0-9 &\-\/]{2,60}$")

_MINERU_VERSION_CACHE: dict[str, str] = {}


@dataclass(slots=True)
class PageData:
    page: int
    text: str


@dataclass(slots=True)
class ParseResult:
    markdown: str
    json_text: str
    page_count: int
    parser_name: str
    parser_version: str
    chunks: list[Chunk] = field(default_factory=list)


@dataclass(slots=True)
class _RawParse:
    markdown: str
    pages: list[PageData]
    blocks: list[TextBlock]
    parser_name: str
    parser_version: str
    title: str = ""


# --------------------------------------------------------------------------- 路径
def resolve_storage_file(relative_path: str) -> Path:
    """把后端传来的相对路径解析为 STORAGE_ROOT 下的真实文件。"""
    settings = get_settings()
    root = settings.storage_root.resolve()
    raw = Path((relative_path or "").strip())
    if not raw:
        raise HTTPException(status_code=400, detail="filePath 不能为空")

    candidate = raw if raw.is_absolute() else root / raw
    try:
        candidate = candidate.resolve()
    except OSError as exc:
        raise HTTPException(status_code=400, detail=f"非法的文件路径：{exc}") from exc

    if candidate != root and not candidate.is_relative_to(root):
        raise HTTPException(status_code=403, detail="文件路径超出 STORAGE_ROOT 范围")
    if not candidate.is_file():
        raise HTTPException(status_code=404, detail=f"文件不存在：{relative_path}")
    return candidate


def mineru_available() -> bool:
    cmd = get_settings().mineru_cmd.strip()
    if not cmd:
        return False
    if shutil.which(cmd):
        return True
    return Path(cmd).is_file()


def effective_parser_name() -> str:
    """健康检查 / 启动日志展示用：mineru-cloud / mineru / pymupdf。"""
    return get_settings().effective_parser


# --------------------------------------------------------------------------- 标题
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
        elif output.endswith("-") and line[:1].islower():
            output = output[:-1] + line
        elif _is_cjk(output[-1]) and _is_cjk(line[0]):
            output += line
        else:
            output += " " + line
    return output


def _body_font_size(weighted_sizes: list[tuple[float, int]]) -> float:
    if not weighted_sizes:
        return 10.0
    ordered = sorted(weighted_sizes, key=lambda item: item[0])
    total = sum(weight for _, weight in ordered)
    if total <= 0:
        return 10.0
    cumulative = 0
    for size, weight in ordered:
        cumulative += weight
        if cumulative >= total / 2:
            return size or 10.0
    return ordered[-1][0] or 10.0


def looks_like_heading(text: str, size: float, bold: bool, body_size: float) -> bool:
    raw = (text or "").strip()
    if not raw or len(raw) > 120:
        return False
    if raw.startswith("#"):
        return True

    lowered = raw.lower().strip(" :：.。、")
    if lowered in _HEADING_KEYWORDS:
        return True
    if _CHINESE_CHAPTER_RE.match(raw):
        return True
    if _CHINESE_NUMBER_RE.match(raw):
        return True
    if _UPPER_HEADING_RE.match(raw) and len(re.findall(r"[A-Z]", raw)) >= 3:
        return True
    if raw.endswith(("。", ".", "，", ",", "；", ";", "！", "!", "？", "?")):
        return False
    # “0.9 个百分点”这类句子也会命中编号正则，因此要求字号更大或加粗
    if _NUMBERED_HEADING_RE.match(raw) and (bold or size >= (body_size or 10.0) + 1.0):
        return True
    if size >= (body_size or 10.0) + 2.0 and len(raw) <= 80:
        return True
    if bold and size >= (body_size or 10.0) - 0.5 and len(raw) <= 60:
        return True
    return False


# --------------------------------------------------------------------------- PyMuPDF
def _extract_page_blocks(page: pymupdf.Page, page_no: int, body_size: float) -> list[TextBlock]:
    blocks: list[TextBlock] = []
    try:
        data = page.get_text("dict", sort=True)
    except Exception as exc:  # noqa: BLE001
        logger.warning("第 %d 页结构化提取失败：%s", page_no, exc)
        data = {}

    for raw_block in data.get("blocks", []):
        if raw_block.get("type") != 0:
            continue
        lines: list[str] = []
        sizes: list[float] = []
        bolds: list[bool] = []
        for line in raw_block.get("lines", []):
            parts: list[str] = []
            for span in line.get("spans", []):
                text = span.get("text") or ""
                if not text.strip():
                    continue
                parts.append(text)
                sizes.append(float(span.get("size") or 0.0))
                font = str(span.get("font") or "").lower()
                flags = int(span.get("flags") or 0)
                bolds.append(bool(flags & 16) or "bold" in font or "black" in font)
            if parts:
                lines.append("".join(parts))
        if not lines:
            continue

        text = _join_lines(lines)
        if not text:
            continue
        if _PAGE_NO_RE.match(text):
            continue

        max_size = max(sizes) if sizes else 0.0
        bold = any(bolds)
        blocks.append(
            TextBlock(
                text=text,
                page=page_no,
                heading=looks_like_heading(text, max_size, bold, body_size),
            )
        )

    if blocks:
        return blocks

    # 某些 PDF 的 dict 模式无内容，退回纯文本按空行分段
    try:
        plain = page.get_text("text", sort=True).strip()
    except Exception:  # noqa: BLE001
        return []
    for paragraph in re.split(r"\n\s*\n", plain):
        text = _join_lines(paragraph.splitlines())
        if not text or _PAGE_NO_RE.match(text):
            continue
        blocks.append(
            TextBlock(
                text=text,
                page=page_no,
                heading=looks_like_heading(text, body_size, False, body_size),
            )
        )
    return blocks


def _parse_with_pymupdf(path: Path, file_name: str | None) -> _RawParse:
    try:
        document = pymupdf.open(path)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=f"无法打开 PDF：{exc}") from exc

    try:
        if document.needs_pass and not document.authenticate(""):
            raise HTTPException(status_code=400, detail="PDF 已加密，无法解析")

        metadata_title = (document.metadata or {}).get("title") or ""
        page_cache: list[tuple[int, pymupdf.Page, str, dict]] = []
        weighted_sizes: list[tuple[float, int]] = []
        pages: list[PageData] = []

        for page_no, page in enumerate(document, start=1):
            try:
                plain = page.get_text("text", sort=True).strip()
            except Exception:  # noqa: BLE001
                plain = ""
            try:
                data = page.get_text("dict", sort=True)
            except Exception:  # noqa: BLE001
                data = {}
            for raw_block in data.get("blocks", []):
                if raw_block.get("type") != 0:
                    continue
                for line in raw_block.get("lines", []):
                    for span in line.get("spans", []):
                        text = span.get("text") or ""
                        if text.strip():
                            weighted_sizes.append((float(span.get("size") or 0.0), len(text)))

            pages.append(PageData(page=page_no, text=plain))
            page_cache.append((page_no, page, plain, data))

        body_size = _body_font_size(weighted_sizes)
        blocks: list[TextBlock] = []
        for page_no, page, plain, _ in page_cache:
            blocks.extend(_extract_page_blocks(page, page_no, body_size))

        first_heading = next(
            (
                block.text
                for block in blocks
                if block.heading
                and block.text.strip().lower().strip(" :：.。、") not in _HEADING_KEYWORDS
            ),
            "",
        )
        title = metadata_title.strip() or first_heading.strip()

        # 单页短文本（常见于演示/测试用 PDF）没有可用元数据时，把首行当标题
        if not title and blocks:
            total_chars = sum(len(page.text) for page in pages)
            candidate = blocks[0].text.strip()
            if (
                len(pages) <= 1
                and total_chars <= 500
                and 0 < len(candidate) <= 200
                and candidate.lower().strip(" :：.。、") not in _HEADING_KEYWORDS
                and not candidate.endswith(("。", ".", "，", ",", "；", ";"))
            ):
                title = candidate
                blocks[0] = TextBlock(text=candidate, page=1, heading=True)

        title = title or (Path(file_name).stem if file_name else path.stem) or "未命名文献"

        if not pages:
            note = "（本文件没有可读取的页面，可能已损坏或为空文件。）"
            pages = [PageData(page=1, text=note)]
            blocks.append(TextBlock(text=note, page=1, heading=False))

        markdown = _build_markdown(title, blocks)
        if not any(page.text.strip() for page in pages):
            note = "（本文件未提取到可读文本，可能为扫描版 PDF；建议启用 OCR 或 MinerU 解析。）"
            markdown = markdown.rstrip() + "\n\n" + note + "\n"
            blocks.append(TextBlock(text=note, page=1, heading=False))
            pages = [PageData(page=page.page, text=page.text or note) for page in pages]

        version = getattr(pymupdf, "__version__", "unknown")
        return _RawParse(
            markdown=markdown,
            pages=pages,
            blocks=blocks,
            parser_name="PyMuPDF",
            parser_version=str(version),
            title=title,
        )
    finally:
        document.close()


# --------------------------------------------------------------------------- Markdown
def _strip_heading_marks(text: str) -> str:
    return re.sub(r"^#{1,6}\s*", "", (text or "").strip()).strip()


def _build_markdown(title: str, blocks: list[TextBlock]) -> str:
    title = _strip_heading_marks(title) or "未命名文献"
    lines: list[str] = [f"# {title}", ""]
    title_written = False

    for block in blocks:
        text = block.text.strip()
        if not text:
            continue
        if block.heading:
            heading = _strip_heading_marks(text)
            if not heading:
                continue
            if not title_written and heading == title:
                title_written = True
                continue
            lines.append(f"## {heading}")
            lines.append("")
        else:
            lines.append(text)
            lines.append("")

    return "\n".join(lines).strip() + "\n"


# --------------------------------------------------------------------------- MinerU
def _mineru_version(executable: str) -> str:
    if executable in _MINERU_VERSION_CACHE:
        return _MINERU_VERSION_CACHE[executable]
    version = "unknown"
    try:
        completed = subprocess.run(
            [executable, "--version"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=15,
            check=False,
        )
        match = re.search(r"\d+\.\d+(?:\.\d+)?", f"{completed.stdout}\n{completed.stderr}")
        if match:
            version = match.group(0)
    except Exception as exc:  # noqa: BLE001
        logger.debug("读取 MinerU 版本失败：%s", exc)
    _MINERU_VERSION_CACHE[executable] = version
    return version


def _run_creation_flags() -> int:
    return getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0


def _blocks_from_content_list(items: list[dict]) -> tuple[list[TextBlock], list[PageData]]:
    blocks: list[TextBlock] = []
    page_texts: dict[int, list[str]] = {}

    for item in items:
        if not isinstance(item, dict):
            continue
        text = (item.get("text") or "").strip()
        if not text:
            continue

        if "page_idx" in item:
            try:
                page = int(item.get("page_idx")) + 1
            except (TypeError, ValueError):
                page = 1
        elif "page" in item:
            try:
                page = max(1, int(item.get("page")))
            except (TypeError, ValueError):
                page = 1
        else:
            page = 1

        item_type = str(item.get("type") or "").lower()
        level = item.get("text_level")
        try:
            heading = item_type in ("title", "heading") or bool(level and int(level) <= 3)
        except (TypeError, ValueError):
            heading = item_type in ("title", "heading")
        if item_type in ("image", "figure"):
            continue

        blocks.append(TextBlock(text=text, page=page, heading=heading))
        page_texts.setdefault(page, []).append(text)

    pages = [
        PageData(page=page, text="\n\n".join(page_texts[page]))
        for page in sorted(page_texts)
    ]
    return blocks, pages


def _blocks_from_markdown(markdown: str) -> list[TextBlock]:
    blocks: list[TextBlock] = []
    paragraph: list[str] = []
    in_code = False

    def flush() -> None:
        nonlocal paragraph
        if paragraph:
            text = _join_lines(paragraph)
            if text:
                blocks.append(TextBlock(text=text, page=1, heading=False))
            paragraph = []

    for raw_line in (markdown or "").splitlines():
        line = raw_line.rstrip()
        if line.strip().startswith("```"):
            in_code = not in_code
            continue
        if in_code:
            paragraph.append(line)
            continue

        heading_match = re.match(r"^(#{1,6})\s+(.+)$", line.strip())
        if heading_match:
            flush()
            blocks.append(TextBlock(text=heading_match.group(2).strip(), page=1, heading=True))
            continue
        if not line.strip():
            flush()
            continue
        if line.strip().startswith("!["):
            continue
        paragraph.append(line)

    flush()
    return blocks


def _try_mineru(path: Path, file_name: str | None) -> _RawParse | None:
    settings = get_settings()
    executable = shutil.which(settings.mineru_cmd) or (
        settings.mineru_cmd if Path(settings.mineru_cmd).is_file() else None
    )
    if not executable:
        return None

    workdir = tempfile.mkdtemp(prefix="scholarai_mineru_")
    try:
        command = [executable, "-p", str(path), "-o", workdir]
        logger.info("调用 MinerU：%s", " ".join(command))
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=settings.mineru_timeout_seconds,
            check=False,
            creationflags=_run_creation_flags(),
        )
        if completed.returncode != 0:
            logger.warning(
                "MinerU 退出码 %s，回退 PyMuPDF：%s",
                completed.returncode,
                (completed.stderr or completed.stdout or "")[-800:],
            )
            return None

        output_dir = Path(workdir)
        markdown_files = sorted(
            output_dir.rglob("*.md"), key=lambda item: item.stat().st_size, reverse=True
        )
        if not markdown_files:
            logger.warning("MinerU 未生成 Markdown，回退 PyMuPDF")
            return None

        markdown = markdown_files[0].read_text(encoding="utf-8", errors="replace").strip()
        if not markdown:
            logger.warning("MinerU 生成的 Markdown 为空，回退 PyMuPDF")
            return None

        content_lists = sorted(
            output_dir.rglob("*content_list.json"),
            key=lambda item: item.stat().st_size,
            reverse=True,
        )
        items: list[dict] = []
        if content_lists:
            try:
                loaded = json.loads(content_lists[0].read_text(encoding="utf-8", errors="replace"))
                if isinstance(loaded, list):
                    items = [item for item in loaded if isinstance(item, dict)]
            except Exception as exc:  # noqa: BLE001
                logger.warning("解析 MinerU content_list 失败：%s", exc)

        blocks, pages = _blocks_from_content_list(items) if items else ([], [])
        if not blocks:
            blocks = _blocks_from_markdown(markdown)

        if not pages:
            # MinerU 的页级 JSON 不可用时，用 PyMuPDF 补齐页文本与页数
            pages = _page_texts_with_pymupdf(path)

        page_count = max(
            [page.page for page in pages] + [block.page for block in blocks] + [0]
        )
        title_match = re.search(r"^#\s+(.+)$", markdown, flags=re.MULTILINE)
        title = title_match.group(1).strip() if title_match else ""
        if not title:
            first_heading = next((block.text for block in blocks if block.heading), "")
            title = first_heading.strip()
        logger.info("MinerU 解析成功：md=%d 字符，blocks=%d，pages=%d", len(markdown), len(blocks), page_count)
        return _RawParse(
            markdown=markdown.rstrip() + "\n",
            pages=pages,
            blocks=blocks,
            parser_name="MinerU",
            parser_version=_mineru_version(executable),
            title=title,
        )
    except subprocess.TimeoutExpired:
        logger.warning("MinerU 解析超时（%s 秒），回退 PyMuPDF", settings.mineru_timeout_seconds)
        return None
    except Exception as exc:  # noqa: BLE001
        logger.warning("MinerU 解析失败，回退 PyMuPDF：%s", exc)
        return None
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


def _mineru_cloud_version() -> str:
    try:
        from importlib.metadata import version

        return version("mineru-open-sdk")
    except Exception:  # noqa: BLE001
        return "unknown"


def _try_mineru_cloud(path: Path) -> _RawParse | None:
    """MinerU 云端 API：适合双栏/表格较多的论文，失败由调用方回退 PyMuPDF。"""
    settings = get_settings()

    if not settings.has_mineru_token:
        return None

    try:
        from mineru import MinerU
    except Exception as exc:  # noqa: BLE001
        logger.warning("MinerU SDK 不可用，跳过云端解析：%s", exc)
        return None

    try:
        client = MinerU(
            token=settings.mineru_token.strip(),
            base_url=settings.mineru_cloud_base_url,
        )
        logger.info(
            "调用 MinerU 云端：file=%s, model=%s, timeout=%ss",
            path.name,
            settings.mineru_model,
            settings.mineru_cloud_timeout_seconds,
        )
        result = client.extract(
            str(path),
            model=settings.mineru_model or None,
            ocr=True if settings.mineru_ocr else None,
            timeout=settings.mineru_cloud_timeout_seconds,
        )

        markdown = (result.markdown or "").strip() if result else ""
        if result is None or result.state != "done" or not markdown:
            logger.warning(
                "MinerU 云端未返回有效 Markdown：state=%s, error=%s",
                getattr(result, "state", None),
                getattr(result, "error", None),
            )
            return None

        blocks, pages = _blocks_from_content_list(result.content_list or [])
        if not blocks:
            blocks = _blocks_from_markdown(markdown)
        if not pages:
            pages = _page_texts_with_pymupdf(path)

        title_match = re.search(r"^#\s+(.+)$", markdown, flags=re.MULTILINE)
        title = title_match.group(1).strip() if title_match else ""
        if not title:
            title = next((block.text.strip() for block in blocks if block.heading), "")

        logger.info(
            "MinerU 云端解析成功：md=%d 字符，blocks=%d，pages=%d",
            len(markdown),
            len(blocks),
            len(pages),
        )
        return _RawParse(
            markdown=markdown.rstrip() + "\n",
            pages=pages,
            blocks=blocks,
            parser_name="MinerU Cloud",
            parser_version=_mineru_cloud_version(),
            title=title,
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("MinerU 云端解析失败，回退 PyMuPDF：%s", exc)
        return None


def _page_texts_with_pymupdf(path: Path) -> list[PageData]:
    try:
        document = pymupdf.open(path)
    except Exception:  # noqa: BLE001
        return []
    try:
        pages: list[PageData] = []
        for page_no, page in enumerate(document, start=1):
            try:
                text = page.get_text("text", sort=True).strip()
            except Exception:  # noqa: BLE001
                text = ""
            pages.append(PageData(page=page_no, text=text))
        return pages
    finally:
        document.close()


# --------------------------------------------------------------------------- 入口
def parse_pdf(file_path: str, file_name: str | None = None) -> ParseResult:
    settings = get_settings()
    path = resolve_storage_file(file_path)

    raw: _RawParse | None = None

    if settings.parser in ("auto", "mineru") and settings.mineru_provider != "off":
        # 1. 云端 MinerU：有 Token 时优先（双栏/表格识别更稳）
        if settings.mineru_provider in ("auto", "cloud") and settings.has_mineru_token:
            raw = _try_mineru_cloud(path)

        # 2. 本地 MinerU CLI（如果装了）
        if raw is None and settings.mineru_provider in ("auto", "local") and mineru_available():
            raw = _try_mineru(path, file_name)

    # 3. 兜底：PyMuPDF
    if raw is None:
        raw = _parse_with_pymupdf(path, file_name)

    chunk_blocks = raw.blocks
    # 文档大标题不是章节；跳过它，避免正文块都挂到标题名下
    if (
        chunk_blocks
        and chunk_blocks[0].heading
        and raw.title
        and chunk_blocks[0].text.strip() == raw.title.strip()
    ):
        chunk_blocks = chunk_blocks[1:]

    chunks = build_chunks(chunk_blocks)
    if not chunks:
        # 例如只有标题的 PDF：标题被识别为 heading，没有正文段落
        fallback_text = "\n\n".join(page.text for page in raw.pages if page.text.strip()).strip()
        if fallback_text:
            chunks = build_chunks([TextBlock(text=fallback_text, page=1, heading=False)])
        else:
            note = "（本文件未提取到可读文本，无法生成有效文本块。）"
            chunks = [Chunk("Page 1", 1, 1, note, estimate_tokens(note))]

    page_count = max(
        len(raw.pages),
        max((page.page for page in raw.pages), default=0),
        max((chunk.page_end for chunk in chunks), default=0),
    )
    if page_count <= 0:
        page_count = 1

    page_payload = {
        "pageCount": page_count,
        "parser": raw.parser_name,
        "parserVersion": raw.parser_version,
        "fileName": file_name or path.name,
        "pages": [{"page": page.page, "text": page.text} for page in raw.pages],
    }
    json_text = json.dumps(page_payload, ensure_ascii=False)

    logger.info(
        "PDF 解析完成：file=%s, parser=%s, pages=%d, blocks=%d, chunks=%d",
        file_path,
        raw.parser_name,
        page_count,
        len(raw.blocks),
        len(chunks),
    )
    return ParseResult(
        markdown=raw.markdown,
        json_text=json_text,
        page_count=page_count,
        parser_name=raw.parser_name,
        parser_version=raw.parser_version,
        chunks=chunks,
    )
