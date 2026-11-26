"""MySQL 访问层。

只做三类事情：
1. 解析任务完成后更新 ``document_chunk.embedding_status/embedding_model``；
2. 分析任务未传 markdownPath 时，按
   ``document.current_parse_record_id -> parse_record.markdown_file_id -> document_file.storage_path``
   反查当前有效解析版本的 Markdown；
3. 健康检查 / 兜底读取文献标题、摘要。

不修改任何表结构。
"""

from __future__ import annotations

import logging
from collections.abc import Iterator, Sequence
from contextlib import contextmanager

import pymysql
from pymysql.cursors import DictCursor

from .config import get_settings

logger = logging.getLogger(__name__)


class DatabaseError(RuntimeError):
    """数据库不可用或查询失败。"""


@contextmanager
def connection() -> Iterator[pymysql.connections.Connection]:
    settings = get_settings()
    conn = pymysql.connect(
        host=settings.mysql_host,
        port=settings.mysql_port,
        user=settings.mysql_user,
        password=settings.mysql_password,
        database=settings.mysql_database,
        charset="utf8mb4",
        cursorclass=DictCursor,
        autocommit=True,
        connect_timeout=settings.mysql_connect_timeout,
        read_timeout=15,
        write_timeout=15,
    )
    try:
        yield conn
    finally:
        conn.close()


def ping() -> bool:
    """探测 MySQL 是否可用；不可用时只记日志，不影响探活接口。"""
    try:
        with connection() as conn:
            with conn.cursor() as cursor:
                cursor.execute("SELECT 1")
                cursor.fetchone()
        return True
    except Exception as exc:  # noqa: BLE001 - 启动期只做可用性探测
        logger.warning("MySQL 不可用：%s", exc)
        return False


def get_document_meta(document_id: int) -> dict | None:
    """读取文献标题/摘要，供分析兜底使用。"""
    sql = """
        SELECT id, title, abstract, user_id, current_parse_record_id
        FROM document
        WHERE id = %s AND deleted = 0
    """
    try:
        with connection() as conn:
            with conn.cursor() as cursor:
                cursor.execute(sql, (document_id,))
                return cursor.fetchone()
    except pymysql.MySQLError as exc:
        raise DatabaseError(f"查询文献失败：{exc}") from exc


def resolve_markdown_path(document_id: int) -> str | None:
    """返回当前有效解析版本 Markdown 的相对路径（相对 STORAGE_ROOT）。

    优先使用 ``document.current_parse_record_id``；为空时退回到最近一条
    ``is_current=1`` 且带 Markdown 文件的解析记录。文献不存在或未解析时返回 None。
    """
    current_sql = """
        SELECT df.storage_path
        FROM document d
        JOIN parse_record pr ON pr.id = d.current_parse_record_id
        JOIN document_file df ON df.id = pr.markdown_file_id
        WHERE d.id = %s AND d.deleted = 0
        LIMIT 1
    """
    latest_sql = """
        SELECT df.storage_path
        FROM parse_record pr
        JOIN document_file df ON df.id = pr.markdown_file_id
        JOIN document d ON d.id = pr.document_id
        WHERE pr.document_id = %s AND d.deleted = 0
        ORDER BY pr.is_current DESC, pr.id DESC
        LIMIT 1
    """
    try:
        with connection() as conn:
            with conn.cursor() as cursor:
                cursor.execute(current_sql, (document_id,))
                row = cursor.fetchone()
                if row and row.get("storage_path"):
                    return str(row["storage_path"])

                cursor.execute(latest_sql, (document_id,))
                row = cursor.fetchone()
                if row and row.get("storage_path"):
                    return str(row["storage_path"])
                return None
    except pymysql.MySQLError as exc:
        raise DatabaseError(f"反查解析文件失败：{exc}") from exc


def update_embedding_status(items: Sequence[tuple[str, str, str]]) -> int:
    """批量更新文本块向量化状态。

    ``items`` 元素为 ``(chunk_id, status, embedding_model)``。
    返回受影响行数；chunk_id 不存在时返回 0，不视为错误。
    """
    if not items:
        return 0

    sql = """
        UPDATE document_chunk
        SET embedding_status = %s, embedding_model = %s
        WHERE chunk_id = %s
    """
    payload = [(status, model, chunk_id) for chunk_id, status, model in items]
    try:
        with connection() as conn:
            with conn.cursor() as cursor:
                affected = cursor.executemany(sql, payload)
                return int(affected or 0)
    except pymysql.MySQLError as exc:
        raise DatabaseError(f"更新向量化状态失败：{exc}") from exc


def get_chunks_by_ids(chunk_ids: Sequence[str]) -> dict[str, dict]:
    """按 chunk_id 批量读取文本块（预留：Milvus 不存 content 时使用）。"""
    if not chunk_ids:
        return {}
    placeholders = ",".join(["%s"] * len(chunk_ids))
    sql = f"""
        SELECT id, chunk_id, user_id, document_id, parse_record_id,
               chunk_index, section, page_start, page_end, content
        FROM document_chunk
        WHERE chunk_id IN ({placeholders})
    """
    try:
        with connection() as conn:
            with conn.cursor() as cursor:
                cursor.execute(sql, tuple(chunk_ids))
                rows = cursor.fetchall()
                return {str(row["chunk_id"]): row for row in rows}
    except pymysql.MySQLError as exc:
        raise DatabaseError(f"查询文本块失败：{exc}") from exc
