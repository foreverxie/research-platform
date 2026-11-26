#!/usr/bin/env python
"""离线/在线冒烟测试。

用法（先启动服务）::

    python scripts/smoke.py
    python scripts/smoke.py --token smoke-token
    python scripts/smoke.py --db-check

``--db-check`` 会临时向 document_chunk 插入一行测试数据，验证
``embedding_status`` 会被写成 SUCCESS，随后删除该行；请仅在开发库使用。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import uuid
from pathlib import Path

import httpx
import pymysql

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.config import get_settings  # noqa: E402
from app.services.chunker import TextBlock, build_chunks  # noqa: E402

CHUNKS = [
    {
        "chunkId": "SMOKE_9001_1_001",
        "userId": 4,
        "documentId": 9001,
        "parseRecordId": 1,
        "section": "引言",
        "pageStart": 1,
        "pageEnd": 1,
        "content": "注意力机制通过计算查询、键和值之间的相关性，为序列中的每个位置分配权重，从而捕捉长距离依赖关系。",
    },
    {
        "chunkId": "SMOKE_9001_1_002",
        "userId": 4,
        "documentId": 9001,
        "parseRecordId": 1,
        "section": "方法",
        "pageStart": 2,
        "pageEnd": 2,
        "content": "Transformer 模型完全基于自注意力机制，不依赖循环神经网络，能够并行处理序列数据，显著提升训练效率。",
    },
    {
        "chunkId": "SMOKE_9001_1_003",
        "userId": 4,
        "documentId": 9001,
        "parseRecordId": 1,
        "section": "相关工作",
        "pageStart": 2,
        "pageEnd": 3,
        "content": "检索增强生成（RAG）将外部知识库的检索结果作为上下文输入大语言模型，缓解模型幻觉并提升回答的事实准确性。",
    },
    {
        "chunkId": "SMOKE_9001_1_004",
        "userId": 4,
        "documentId": 9001,
        "parseRecordId": 1,
        "section": "实验",
        "pageStart": 3,
        "pageEnd": 3,
        "content": "U-Net 采用编码器-解码器结构与跳跃连接，在医学图像分割任务中能够同时利用浅层细节与深层语义信息。",
    },
    {
        "chunkId": "SMOKE_9001_1_005",
        "userId": 4,
        "documentId": 9001,
        "parseRecordId": 1,
        "section": "结论",
        "pageStart": 4,
        "pageEnd": 4,
        "content": "知识图谱通过实体与关系建模组织科研知识，可用于文献推荐、主题发现与跨领域关联分析。",
    },
]


class Smoke:
    def __init__(self, base_url: str, token: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.client = httpx.Client(timeout=60.0, headers=self._headers())
        self.steps: list[dict] = []
        self.failures: list[str] = []
        self.health: dict = {}

    def _headers(self) -> dict[str, str]:
        return {"X-Internal-Token": self.token} if self.token else {}

    def close(self) -> None:
        self.client.close()

    def check(self, step: str, name: str, condition: bool, detail: str = "") -> bool:
        status = "PASS" if condition else "FAIL"
        line = f"[{status}] {step} {name}" + (f" | {detail}" if detail else "")
        print(line)
        self.steps.append({"step": step, "check": name, "status": status, "detail": detail})
        if not condition:
            self.failures.append(f"{step} {name}: {detail}")
        return condition

    def call(self, method: str, path: str, payload: dict | None = None) -> httpx.Response:
        return self.client.request(method, self.base_url + path, json=payload)

    # ------------------------------------------------------------------ 步骤
    def health_check(self) -> None:
        step = "1/11 健康检查"
        try:
            response = self.call("GET", "/internal/v1/health")
            self.check(step, "HTTP 200", response.status_code == 200, str(response.status_code))
            payload = response.json()
            self.health = payload
            self.check(step, "status=UP", payload.get("status") == "UP", str(payload.get("status")))
            self.check(step, "version", payload.get("version") == "1.0.0", str(payload.get("version")))
            fields = ("parser", "embedding", "llm", "vectorStore")
            self.check(step, "字段完整", all(payload.get(key) for key in fields), json.dumps(payload, ensure_ascii=False))
            print(
                f"       parser={payload.get('parser')} embedding={payload.get('embedding')} "
                f"llm={payload.get('llm')} vectorStore={payload.get('vectorStore')}"
            )
        except Exception as exc:  # noqa: BLE001
            self.check(step, "请求成功", False, str(exc))

    def token_check(self) -> None:
        step = "2/11 内部令牌"
        if not self.token:
            print(f"[SKIP] {step} 未传入 --token（服务端 INTERNAL_TOKEN 为空）")
            self.steps.append({"step": step, "check": "token", "status": "SKIP", "detail": ""})
            return
        with httpx.Client(timeout=10.0) as anonymous:
            response = anonymous.get(self.base_url + "/internal/v1/health")
        self.check(step, "无令牌返回 401", response.status_code == 401, str(response.status_code))
        response = self.client.get(
            self.base_url + "/internal/v1/health", headers={"X-Internal-Token": "wrong"}
        )
        self.check(step, "错误令牌返回 401", response.status_code == 401, str(response.status_code))

    def chunker_check(self) -> None:
        step = "3/11 分块算法"
        paragraph = "医学图像分割是计算机辅助诊断的重要任务。" * 12
        chunks = build_chunks(
            [
                TextBlock("1 引言", 1, True),
                TextBlock(paragraph, 1),
                TextBlock(paragraph, 1),
                TextBlock(paragraph, 1),
                TextBlock("2 研究方法", 2, True),
                TextBlock(paragraph, 2),
                TextBlock(paragraph, 2),
            ]
        )
        sizes = [len(chunk.content) for chunk in chunks]
        self.check(step, "产生多个块", len(chunks) >= 2, str(sizes))
        self.check(step, "块长度 600-1000", all(550 <= size <= 1000 for size in sizes), str(sizes))
        overlap_ok = chunks[0].content[-100:] == chunks[1].content[:100] if len(chunks) >= 2 else False
        self.check(step, "块间重叠 100 字符", overlap_ok)
        self.check(step, "section 非空", all(chunk.section.strip() for chunk in chunks))

    def parse_check(self) -> None:
        step = "4/11 PDF 解析"
        response = self.call(
            "POST",
            "/internal/v1/documents/parse",
            {
                "documentId": 1,
                "parseRecordId": 1,
                "taskId": 1,
                "filePath": "documents/1/original.pdf",
                "fileName": "original.pdf",
            },
        )
        self.check(step, "HTTP 200", response.status_code == 200, str(response.status_code))
        if response.status_code != 200:
            print(response.text[:500])
            return
        payload = response.json()
        chunks = payload.get("chunks") or []
        self.check(step, "markdown 非空", bool((payload.get("markdown") or "").strip()))
        self.check(step, "pageCount>=1", (payload.get("pageCount") or 0) >= 1, str(payload.get("pageCount")))
        self.check(step, "chunks>0", len(chunks) > 0, str(len(chunks)))
        self.check(step, "chunk 字段完整", all(c.get("content") and c.get("section") for c in chunks))
        self.check(step, "parserName", bool(payload.get("parserName")), str(payload.get("parserName")))
        try:
            page_json = json.loads(payload.get("json") or "{}")
        except json.JSONDecodeError:
            page_json = {}
        self.check(step, "json 可解析且含 pages", isinstance(page_json.get("pages"), list), str(page_json.keys()))
        print(
            f"       parser={payload.get('parserName')} v{payload.get('parserVersion')} "
            f"pages={payload.get('pageCount')} chunks={len(chunks)} md={len(payload.get('markdown') or '')}字符"
        )

    def path_guard_check(self) -> None:
        step = "5/11 路径穿越防护"
        response = self.call(
            "POST",
            "/internal/v1/documents/parse",
            {
                "documentId": 1,
                "parseRecordId": 1,
                "taskId": 1,
                "filePath": "../research-platform-web/package.json",
                "fileName": "package.json",
            },
        )
        self.check(step, "拒绝越界路径", response.status_code in (400, 403), str(response.status_code))

    def index_check(self) -> None:
        step = "6/11 向量化"
        response = self.call("POST", "/internal/v1/embeddings/index", {"chunks": CHUNKS})
        self.check(step, "HTTP 200", response.status_code == 200, str(response.status_code))
        payload = response.json() if response.status_code == 200 else {}
        self.check(step, "indexed=5", payload.get("indexed") == 5, json.dumps(payload, ensure_ascii=False))
        self.check(step, "failed=0", payload.get("failed") == 0)

        # 重复调用：验证按 documentId 删除旧向量 + upsert 幂等
        response = self.call("POST", "/internal/v1/embeddings/index", {"chunks": CHUNKS})
        payload = response.json() if response.status_code == 200 else {}
        self.check(step, "重复索引幂等", payload.get("indexed") == 5)

    def search_check(self) -> None:
        step = "7/11 语义检索"
        response = self.call(
            "POST",
            "/internal/v1/search/semantic",
            {
                "userId": 4,
                "query": "注意力机制如何捕捉长距离依赖？",
                "documentIds": [9001],
                "topK": 5,
            },
        )
        self.check(step, "HTTP 200", response.status_code == 200, str(response.status_code))
        payload = response.json() if response.status_code == 200 else {}
        hits = payload.get("hits") or []
        self.check(step, "命中结果非空", len(hits) > 0, str(len(hits)))
        self.check(step, "最高分 > 0", bool(hits) and hits[0].get("score", 0) > 0, str(hits[0].get("score") if hits else None))
        self.check(step, "Top1 与问题相关", bool(hits) and ("注意力" in hits[0].get("content", "") or "Attention" in hits[0].get("content", "")))
        if hits:
            print(f"       Top1 chunk={hits[0].get('chunkId')} score={hits[0].get('score'):.4f} section={hits[0].get('section')}")

        # documentIds 过滤：不存在的文献应返回空
        response = self.call(
            "POST",
            "/internal/v1/search/semantic",
            {"userId": 4, "query": "注意力机制", "documentIds": [123456], "topK": 5},
        )
        payload = response.json() if response.status_code == 200 else {}
        self.check(step, "documentIds 过滤生效", payload.get("hits") == [])

    def rag_check(self) -> None:
        step = "8/11 RAG 问答"
        response = self.call(
            "POST",
            "/internal/v1/rag/answer",
            {
                "userId": 4,
                "question": "注意力机制如何捕捉长距离依赖？",
                "documentIds": [9001],
                "topK": 4,
            },
        )
        self.check(step, "HTTP 200", response.status_code == 200, str(response.status_code))
        payload = response.json() if response.status_code == 200 else {}
        answer = payload.get("answer") or ""
        references = payload.get("references") or []
        self.check(step, "answer 非空", bool(answer.strip()))
        self.check(step, "references 非空", len(references) > 0, str(len(references)))
        self.check(step, "reference 字段完整", all(
            ref.get("documentId") and ref.get("chunkId") and ref.get("section") and ref.get("snippet")
            for ref in references
        ))
        if self.health.get("llm") == "local":
            self.check(step, "抽取式回答", "根据文献库检索到的" in answer)
            self.check(step, "modelName=local-fallback", payload.get("modelName") == "local-fallback")
        print(f"       model={payload.get('modelName')} refs={len(references)} tokens={payload.get('promptTokens')}/{payload.get('completionTokens')}")

    def rag_empty_check(self) -> None:
        step = "9/11 无证据问答"
        response = self.call(
            "POST",
            "/internal/v1/rag/answer",
            {"userId": 4, "question": "完全不存在的主题 XYZ", "documentIds": [123456], "topK": 3},
        )
        payload = response.json() if response.status_code == 200 else {}
        self.check(step, "HTTP 200", response.status_code == 200, str(response.status_code))
        self.check(step, "references 为空", payload.get("references") == [])
        self.check(step, "answer 说明证据不足", "未找到" in (payload.get("answer") or "") or "证据不足" in (payload.get("answer") or ""))

    def analyze_check(self) -> None:
        step = "10/11 文献分析"
        response = self.call(
            "POST",
            "/internal/v1/documents/analyze",
            {
                "documentId": 1,
                "taskId": 1,
                "analysisType": "FULL_ANALYSIS",
                "title": "Attention Is All You Need",
                "abstractText": (
                    "本文提出 Transformer 模型，完全基于注意力机制，摒弃循环与卷积结构。"
                    "注意力机制通过计算查询、键和值的相关性捕捉长距离依赖。"
                    "实验在机器翻译任务上取得领先性能，BLEU 指标显著提升，并具有更好的并行性。"
                ),
                "markdownPath": "",
            },
        )
        self.check(step, "HTTP 200", response.status_code == 200, str(response.status_code))
        payload = response.json() if response.status_code == 200 else {}
        text_fields = (
            "summary",
            "researchBackground",
            "researchMethod",
            "experimentalResults",
            "conclusion",
        )
        self.check(step, "文本字段非空", all((payload.get(field) or "").strip() for field in text_fields))
        self.check(step, "innovationPoints 非空", bool(payload.get("innovationPoints")))
        self.check(step, "keywords 非空", bool(payload.get("keywords")))
        if self.health.get("llm") == "local":
            self.check(step, "modelName=local-fallback", payload.get("modelName") == "local-fallback")
        print(
            f"       model={payload.get('modelName')} keywords={payload.get('keywords')} "
            f"innovations={len(payload.get('innovationPoints') or [])}"
        )

    def db_status_check(self) -> None:
        step = "11/11 向量化状态回写"
        settings = get_settings()
        chunk_id = f"SMOKE_DB_{uuid.uuid4().hex[:12]}"
        chunk_index = 900000 + int(time.time()) % 100000
        connection = pymysql.connect(
            host=settings.mysql_host,
            port=settings.mysql_port,
            user=settings.mysql_user,
            password=settings.mysql_password,
            database=settings.mysql_database,
            charset="utf8mb4",
            autocommit=True,
            connect_timeout=5,
        )
        inserted = False
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT id FROM user WHERE id = 4")
                if cursor.fetchone() is None:
                    self.check(step, "测试用户存在", False, "user 4 不存在")
                    return
                cursor.execute(
                    """
                    INSERT INTO document_chunk
                        (chunk_id, user_id, document_id, parse_record_id, chunk_index,
                         section, page_start, page_end, content, token_count, embedding_status)
                    VALUES (%s, 4, 1, 1, %s, %s, 1, 1, %s, 8, 'PENDING')
                    """,
                    (chunk_id, chunk_index, "冒烟测试", "冒烟测试文本块，用于验证 embedding_status 回写。"),
                )
                inserted = True

            response = self.call(
                "POST",
                "/internal/v1/embeddings/index",
                {
                    "chunks": [
                        {
                            "chunkId": chunk_id,
                            "userId": 4,
                            "documentId": 9001,
                            "parseRecordId": 1,
                            "section": "冒烟测试",
                            "pageStart": 1,
                            "pageEnd": 1,
                            "content": "冒烟测试文本块，用于验证 embedding_status 回写。",
                        }
                    ]
                },
            )
            self.check(step, "索引调用成功", response.status_code == 200 and response.json().get("indexed") == 1)

            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT embedding_status, embedding_model FROM document_chunk WHERE chunk_id = %s",
                    (chunk_id,),
                )
                row = cursor.fetchone()
            self.check(step, "embedding_status=SUCCESS", bool(row) and row[0] == "SUCCESS", str(row))
            self.check(step, "embedding_model 非空", bool(row) and bool(row[1]), str(row))
        except Exception as exc:  # noqa: BLE001
            self.check(step, "数据库验证", False, str(exc))
        finally:
            if inserted:
                try:
                    with connection.cursor() as cursor:
                        cursor.execute("DELETE FROM document_chunk WHERE chunk_id = %s", (chunk_id,))
                except Exception as exc:  # noqa: BLE001
                    print(f"       [WARN] 清理临时文本块失败：{exc}")
            connection.close()
            # 恢复 doc 9001 的 5 条演示向量，清掉刚才的单条测试向量
            try:
                self.call("POST", "/internal/v1/embeddings/index", {"chunks": CHUNKS})
            except Exception:  # noqa: BLE001
                pass

    # ------------------------------------------------------------------ 主流程
    def run(self, db_check: bool) -> int:
        print("=" * 80)
        print("ScholarAI AI Service · 冒烟测试")
        print(f"base_url={self.base_url} token={'on' if self.token else 'off'} db_check={db_check}")
        print("=" * 80)
        self.health_check()
        self.token_check()
        self.chunker_check()
        self.parse_check()
        self.path_guard_check()
        self.index_check()
        self.search_check()
        self.rag_check()
        self.rag_empty_check()
        self.analyze_check()
        if db_check:
            self.db_status_check()
        else:
            print("[SKIP] 11/11 向量化状态回写（未传 --db-check，避免写入数据库）")
            self.steps.append({"step": "11/11 向量化状态回写", "check": "db", "status": "SKIP", "detail": ""})

        print("-" * 80)
        if self.failures:
            print(f"失败 {len(self.failures)} 项：")
            for failure in self.failures:
                print("  -", failure)
            code = 1
        else:
            print("全部检查通过 ✅")
            code = 0

        report = {
            "baseUrl": self.base_url,
            "tokenEnabled": bool(self.token),
            "health": self.health,
            "steps": self.steps,
            "failures": self.failures,
        }
        report_path = ROOT / "data" / "smoke_report.json"
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"报告：{report_path}")
        return code


def main() -> int:
    parser = argparse.ArgumentParser(description="ScholarAI AI Service 冒烟测试")
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument(
        "--token",
        default=os.environ.get("AI_INTERNAL_TOKEN", os.environ.get("INTERNAL_TOKEN", "")),
    )
    parser.add_argument("--db-check", action="store_true", help="临时插入一行验证 embedding_status 回写")
    args = parser.parse_args()

    smoke = Smoke(args.base_url, args.token.strip())
    try:
        return smoke.run(db_check=args.db_check)
    finally:
        smoke.close()


if __name__ == "__main__":
    raise SystemExit(main())
