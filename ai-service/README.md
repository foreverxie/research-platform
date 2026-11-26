# ScholarAI AI Service

科研文献管理与知识分析平台的 **Python AI 服务**（FastAPI），供 Spring Boot 后端通过
`/internal/v1/*` 内部接口调用。

设计目标：

- **无模型 Key 也能完整跑通**：Embedding / LLM 自动降级为本地确定性算法；
- **无 Milvus 也能检索**：自动回退到 numpy + JSON 本地向量库；
- **无 MinerU 也能解析**：云端 MinerU 未配置 / 失败时自动回退 PyMuPDF；
- **支持 MinerU 云端 API**：配置 `MINERU_TOKEN` 后优先用云端版面分析（双栏/表格论文更稳）；
- **不改 Java 代码、不改数据库结构**：只读 MySQL，唯一写入是
  `document_chunk.embedding_status/embedding_model`。

---

## 1. 目录结构

```
ai-service/
├─ app/
│  ├─ main.py                 # FastAPI 入口、lifespan 启动日志、异常处理
│  ├─ config.py               # pydantic-settings，读取 .env
│  ├─ schemas.py              # 与 Java DTO 严格一致的 camelCase 模型
│  ├─ db.py                   # pymysql：markdown 路径反查、embedding_status 回写
│  ├─ security.py             # X-Internal-Token 校验
│  ├─ container.py            # 服务容器
│  ├─ api/
│  │  ├─ health.py            # GET  /internal/v1/health
│  │  ├─ documents.py         # POST /internal/v1/documents/parse|analyze
│  │  ├─ rag.py               # POST /internal/v1/rag/answer
│  │  ├─ search.py            # POST /internal/v1/search/semantic
│  │  └─ embeddings.py        # POST /internal/v1/embeddings/index
│  └─ services/
│     ├─ parser.py            # MinerU 云端/本地 + PyMuPDF 兜底 + Markdown/页级 JSON
│     ├─ chunker.py           # 600–900 字符分块，重叠 100
│     ├─ embedding.py         # DashScope/OpenAI 兼容 + 本地 sha256 哈希兜底
│     ├─ vector_store.py      # Milvus + 本地 numpy 向量库
│     ├─ llm.py               # chat/completions 调用；无 Key 返回不可用
│     ├─ analyzer.py          # 结构化分析（LLM JSON / 启发式）
│     ├─ rag.py               # 检索、引用、抽取式/模型回答
│     └─ indexer.py           # 批量向量化、删除旧向量、状态回写
├─ scripts/smoke.py           # 11 步冒烟测试
├─ data/vectors/              # 本地向量库（运行时生成）
├─ .env.example
├─ pyproject.toml
└─ README.md
```

---

## 2. 快速开始

### 2.1 配置

```powershell
cd ai-service
Copy-Item .env.example .env
```

默认 `.env.example` 就是**离线演示配置**（无 Key、Milvus 不可用时自动回退）。
`STORAGE_ROOT` 默认按此仓库的兄弟目录布局指向 `research-platform/storage`；
独立部署时请按实际存储路径修改。

### 2.2 启动

```powershell
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

也可以使用 `APP_PORT` 配置：

```powershell
python -m app.main
```

探活：

```powershell
curl.exe http://127.0.0.1:8000/internal/v1/health
# {"status":"UP","version":"1.0.0","parser":"pymupdf","embedding":"local","llm":"local","vectorStore":"local"}
```

Swagger：<http://127.0.0.1:8000/docs>（内部接口，生产环境建议只暴露给后端）。

### 2.3 与 Java 后端联调

后端通过环境变量启用（无需改代码）：

```powershell
$env:AI_SERVICE_ENABLED='true'
$env:AI_SERVICE_BASE_URL='http://localhost:8000'
$env:AI_INTERNAL_TOKEN=''   # 与 Python 的 INTERNAL_TOKEN 保持一致
```

对应后端配置项见 `research-platform/src/main/resources/application.yml` 的
`research.ai.*`。

---

## 3. 接口清单

所有接口的字段名与
`com.research.platform.infrastructure.ai.AiCommands / AiResults` 完全一致。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/internal/v1/health` | 健康检查 |
| POST | `/internal/v1/documents/parse` | PDF → Markdown + 页级 JSON + 文本块 |
| POST | `/internal/v1/documents/analyze` | 结构化文献分析 |
| POST | `/internal/v1/documents/metadata` | 从 PDF 元信息与首页正文识别标题/作者/期刊/DOI/摘要/关键词 |
| POST | `/internal/v1/knowledge/extract` | 从论文原文抽取知识实体（方法/模型/数据集/机构等）与关系 |
| POST | `/internal/v1/rag/answer` | 基于文献库的 RAG 问答 |
| POST | `/internal/v1/search/semantic` | 文本块语义检索 |
| POST | `/internal/v1/embeddings/index` | 文本块批量向量化（后端解析成功后调用） |
| POST | `/internal/v1/embeddings/cleanup` | 发布新版本后清理该文献的旧版本向量 |

### 3.1 `GET /health`

```json
{
  "status": "UP",
  "version": "1.0.0",
  "parser": "pymupdf",
  "embedding": "local",
  "llm": "local",
  "vectorStore": "local"
}
```

`parser` 为 `pymupdf|mineru|mineru-cloud`；`embedding`/`llm` 为 `local|dashscope|openai`；
`vectorStore` 为 `local|milvus`（Milvus 初始化/运行失败会实时变成 `local`）。

### 3.2 `POST /documents/parse`

请求：

```json
{
  "documentId": 1,
  "parseRecordId": 1,
  "taskId": 1,
  "filePath": "documents/1/original.pdf",
  "fileName": "original.pdf"
}
```

返回：

```json
{
  "markdown": "# 标题\n\n正文……",
  "json": "{\"pageCount\":1,\"pages\":[{\"page\":1,\"text\":\"……\"}]}",
  "pageCount": 1,
  "parserName": "PyMuPDF",
  "parserVersion": "2.1",
  "chunks": [
    {"section":"摘要","pageStart":1,"pageEnd":1,"content":"……","tokenCount":123}
  ]
}
```

- `filePath` 相对 `STORAGE_ROOT`；越界路径返回 403，文件不存在返回 404；
- 解析优先级：**MinerU 云端**（`MINERU_TOKEN` 非空且 `MINERU_PROVIDER=auto|cloud`）→ 本地 MinerU CLI（`PARSER=auto` 且命令存在）→ PyMuPDF；失败/超时逐级回退；
- Markdown 按标题/段落生成；页级 JSON 包含每页纯文本；
- 分块目标 600–900 字符、重叠 100；`section` 取最近标题，无标题用 `Page N`。

### 3.3 `POST /documents/analyze`

请求：

```json
{
  "documentId": 1,
  "taskId": 1,
  "analysisType": "FULL_ANALYSIS",
  "title": "Attention Is All You Need",
  "abstractText": "……",
  "markdownPath": ""
}
```

- `markdownPath` 为空时，按
  `document.current_parse_record_id → parse_record.markdown_file_id → document_file.storage_path`
  从 MySQL 反查当前有效解析版本；查不到则退回 `abstractText`；
- 有 LLM Key 时要求模型输出 JSON；无 Key 时启发式抽取，`modelName=local-fallback`；
- 所有文本字段保证非空，缺失信息写 `原文未明确陈述`。

### 3.4 `POST /rag/answer`

- 按 `userId` 过滤向量；`documentIds` 非空时再按文献过滤；
- 有 LLM：基于检索片段回答，要求 `[1]`、`[2]` 引用编号；
- 无 LLM：抽取式回答（“根据文献库检索到的 N 段相关原文……”+ 文献/章节/页码/摘录）；
- 无检索结果：明确说明证据不足，`references=[]`，不编造。

### 3.5 `POST /search/semantic`

返回 `{hits:[{documentId, chunkId, section, pageStart, pageEnd, content, score}]}`；
`score` 为余弦相似度，按降序排列。

### 3.6 `POST /embeddings/index`

请求：

```json
{
  "chunks": [
    {
      "chunkId": "CHUNK_1_2_001",
      "userId": 4,
      "documentId": 1,
      "parseRecordId": 2,
      "section": "摘要",
      "pageStart": 1,
      "pageEnd": 1,
      "content": "……"
    }
  ]
}
```

返回 `{indexed, failed, message}`：

- 先按 `documentId` 删除旧向量，再批量 embedding + upsert（重复解析场景）；
- 更新 `document_chunk.embedding_status` 为 `SUCCESS/FAILED`，`embedding_model` 写模型名；
- **嵌入失败不抛 500**：返回 `indexed=0, failed=n, message=原因`，解析任务不受影响。

### 3.7 鉴权

`INTERNAL_TOKEN` 非空时，所有 `/internal/v1/*` 请求都必须带：

```
X-Internal-Token: <INTERNAL_TOKEN>
```

缺失或错误返回 401。为空则跳过校验（与后端默认值一致）。

---

## 4. 降级策略

| 场景 | 行为 |
|---|---|
| 无 `DASHSCOPE_API_KEY` / `OPENAI_API_KEY` | Embedding 用 sha256 分词哈希（1024 维，L2 归一化）；LLM 用启发式/抽取式 |
| 显式配置远程 provider 但 Key 为空 | 自动回落 `local`，启动日志提示 |
| Milvus 未启动 / 连接失败 / 运行时报错 | 初始化回退本地向量库；运行中出错则切本地并打 ERROR 日志 |
| MinerU 云端鉴权失败 / 额度不足 / 超时 | 回退本地 MinerU（若安装），再回退 PyMuPDF |
| MinerU 未安装 / 退出码非 0 / 超时 | 回退 PyMuPDF |
| MySQL 不可用 | 健康检查仍返回 UP（只记日志）；分析用请求里的标题/摘要；向量化状态无法回写 |
| 扫描版 PDF 无文本层 | 返回提示性文本块；不做 OCR |

---

## 5. 配置项

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `APP_PORT` | `8000` | `python -m app.main` 使用 |
| `APP_VERSION` | `1.0.0` | 健康检查版本 |
| `LOG_LEVEL` | `INFO` | 日志级别 |
| `STORAGE_ROOT` | `../research-platform/storage` | 文件根目录；独立部署时按实际路径配置 |
| `INTERNAL_TOKEN` | 空 | 内部令牌；空则跳过校验 |
| `REQUEST_TIMEOUT_SECONDS` | `60` | 远程模型超时 |
| `MYSQL_HOST/PORT/USER/PASSWORD/DATABASE` | `localhost/3306/root/(empty)/research_knowledge_db` | MySQL；请在本地 `.env` 中配置密码 |
| `EMBEDDING_PROVIDER` | `auto` | `auto|local|dashscope|openai` |
| `EMBEDDING_MODEL` | `text-embedding-v3` | 远程 embedding 模型 |
| `EMBEDDING_DIM` | `1024` | 向量维度；本地/远程/Milvus 必须一致 |
| `EMBEDDING_BATCH_SIZE` | `10` | 远程批量大小 |
| `DASHSCOPE_API_KEY` | 空 | DashScope Key |
| `OPENAI_API_KEY` | 空 | OpenAI 兼容 Key |
| `OPENAI_BASE_URL` | 空 | 含版本段，如 `https://api.openai.com/v1` |
| `LLM_PROVIDER` | `auto` | `auto|local|dashscope|openai` |
| `LLM_MODEL` | `qwen-plus` | 对话模型 |
| `LLM_TEMPERATURE` | `0.2` | 采样温度 |
| `LLM_MAX_TOKENS` | `2000` | 最大输出 |
| `VECTOR_STORE` | `milvus` | `milvus|local` |
| `MILVUS_URI` | `http://localhost:19531` | 独立 Milvus 地址（`research-platform-milvus`） |
| `MILVUS_COLLECTION` | `paper_chunks` | 集合名（自动创建） |
| `MILVUS_TIMEOUT_SECONDS` | `5` | 连接/查询超时 |
| `LOCAL_VECTOR_DIR` | `./data/vectors` | 本地向量目录（相对 ai-service） |
| `PARSER` | `auto` | `auto|mineru|pymupdf` |
| `MINERU_PROVIDER` | `auto` | `auto|cloud|local|off`，有 Token 时 auto 优先云端 |
| `MINERU_TOKEN` | 空 | MinerU 云端 API Token（[mineru.net](https://mineru.net)） |
| `MINERU_MODEL` | `pipeline` | `pipeline|vlm`，vlm 对复杂版面更强但更慢 |
| `MINERU_OCR` | `false` | 扫描版 PDF 建议开启 |
| `MINERU_CLOUD_TIMEOUT_SECONDS` | `600` | 云端任务轮询超时 |
| `MINERU_CMD` | `mineru` | 本地 CLI 可执行文件名或绝对路径 |
| `MINERU_TIMEOUT_SECONDS` | `600` | MinerU 超时 |
| `ANALYZE_MAX_CHARS` | `12000` | 送入 LLM 的正文上限 |
| `RAG_MAX_CONTEXT_CHARS` | `8000` | RAG 证据上限 |

完整示例见 `.env.example`。

---

## 6. 接入真实 Qwen / DashScope

在 `.env` 中：

```dotenv
EMBEDDING_PROVIDER=dashscope
EMBEDDING_MODEL=text-embedding-v3
EMBEDDING_DIM=1024
LLM_PROVIDER=dashscope
LLM_MODEL=qwen-plus
DASHSCOPE_API_KEY=sk-xxxxxxxx
```

- Embedding 调用
  `https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings`；
- LLM 调用
  `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`；
- 只要配置了 `DASHSCOPE_API_KEY`，`*_PROVIDER=auto` 就会自动切到 dashscope；
- 模型名可按账号权限替换为 `qwen-turbo`、`qwen-max`、`text-embedding-v4` 等。

### OpenAI 兼容

```dotenv
EMBEDDING_PROVIDER=openai
EMBEDDING_MODEL=text-embedding-3-small
EMBEDDING_DIM=1024
OPENAI_API_KEY=sk-xxxxxxxx
OPENAI_BASE_URL=https://api.openai.com/v1
LLM_PROVIDER=openai
LLM_MODEL=gpt-4o-mini
```

> 切换 Embedding 模型/维度后：本地向量库需清空 `data/vectors/`；
> Milvus 需删除旧 collection 或使用新的 `MILVUS_COLLECTION`，否则维度校验会拒绝启动并回退本地。

---

## 7. 接入 Milvus

保持：

```dotenv
VECTOR_STORE=milvus
MILVUS_URI=http://localhost:19531
MILVUS_COLLECTION=paper_chunks
EMBEDDING_DIM=1024
```

首次启动会自动创建集合，字段：

```
chunk_id(VARCHAR 64, PK) | user_id(INT64) | document_id(INT64)
parse_record_id(INT64) | section(VARCHAR 512)
page_start(INT32) | page_end(INT32) | content(VARCHAR 65535)
embedding_model(VARCHAR 100) | vector(FLOAT_VECTOR, dim=EMBEDDING_DIM)
```

索引：`FLAT` + `COSINE`。若 Milvus 不可用，启动日志会打印
`Milvus 初始化失败，回退本地向量库：...`，`/health` 返回 `vectorStore=local`。

项目自带一套独立的 Milvus 单机栈（不影响其他项目的 Milvus）：

```powershell
cd ..\research-platform\docker\milvus
docker compose up -d
# Milvus: localhost:19531, metrics: localhost:9092
```

注意：

- 本地向量库与 Milvus **不会双写**；从 Milvus 切到本地后，已有文献需要重新调用
  `/embeddings/index` 才能被本地库检索到；
- 不要用多个 uvicorn worker 同时写本地向量库（文件锁只保证单进程内安全）。

---

## 8. MySQL 使用

**只读查询**：

- `document`：标题、摘要、`current_parse_record_id`；
- `parse_record`：`markdown_file_id`、`is_current`；
- `document_file`：`storage_path`；
- `document_chunk`：按需查询。

**唯一写入**：

```sql
UPDATE document_chunk
SET embedding_status = %s, embedding_model = %s
WHERE chunk_id = %s
```

不会插入/删除文本块，也不改表结构。文本块由 Java 后端解析成功后落库，再调用
`/embeddings/index`。

---

## 9. 解析 / 分块 / 检索策略

- **标题识别**：字号大于正文、加粗、编号（`1 Introduction` / `一、` / `第 1 章`）、
  常见章节名（Abstract / Introduction / 摘要 / 参考文献…）；
- **分块**：按段落累计到 600 字左右切块，最长 900；块间保留 100 字符重叠；
  跨短章节时取“贡献字符最多”的章节作为 `section`；末尾小块会向前合并；
- **本地 Embedding**：英文词 + 中文单字/二元/三元组做 sha256 哈希到 1024 维，
  带符号累加后 L2 归一化；确定性、无需模型；
- **RAG 无 Key**：抽取式回答，逐条给出文献 ID、章节、页码、原文摘录；
- **RAG 有 Key**：把 TopK 片段（含编号）作为证据交给模型，要求引用编号并禁止编造。

---

## 10. 冒烟测试

先启动服务，再执行：

```powershell
cd ai-service

# 离线模式（服务端 INTERNAL_TOKEN 为空）
python scripts\smoke.py

# 验证令牌：服务端需用同一令牌启动
$env:INTERNAL_TOKEN='smoke-token'
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
python scripts\smoke.py --token smoke-token

# 额外验证 document_chunk.embedding_status 回写（临时插入一行再删除）
python scripts\smoke.py --token smoke-token --db-check
```

覆盖内容：

1. `/health` 200 与六个字段；
2. 无令牌/错误令牌 401；
3. 分块大小与 100 字符重叠；
4. 真实文件 `documents/1/original.pdf` 解析；
5. `../` 路径穿越拦截；
6. 5 个中文 chunk 向量化 + 重复索引幂等；
7. 中文语义检索命中且 `score>0`、`documentIds` 过滤；
8. RAG 抽取式回答与引用；
9. 无证据时 `references=[]`；
10. `/analyze` 全字段非空（`local-fallback`）；
11. `--db-check` 时验证 `embedding_status=SUCCESS`、`embedding_model` 非空。

本机离线运行结果（摘要）：

```
[PASS] 1/11 健康检查 字段完整 | {"status":"UP","version":"1.0.0","parser":"pymupdf","embedding":"local","llm":"local","vectorStore":"local"}
[PASS] 2/11 内部令牌 无令牌返回 401 | 401
[PASS] 3/11 分块算法 块长度 600-1000 | [724, 584]
[PASS] 4/11 PDF 解析 chunks>0 | 1
[PASS] 5/11 路径穿越防护 拒绝越界路径 | 403
[PASS] 6/11 向量化 indexed=5 | {"indexed":5,"failed":0,"message":null}
[PASS] 7/11 语义检索 最高分 > 0 | 0.428637
[PASS] 8/11 RAG 问答 references 非空 | 4
[PASS] 9/11 无证据问答 references 为空
[PASS] 10/11 文献分析 modelName=local-fallback
[PASS] 11/11 向量化状态回写 embedding_status=SUCCESS | ('SUCCESS','local-hash-v1')
全部检查通过 ✅
```

报告写入 `data/smoke_report.json`。冒烟测试会向本地向量库写入
`documentId=9001` 的 5 条演示向量（真实文献不受影响）；如需清空，删除
`data/vectors/` 后重启服务即可。

---

## 11. 已知限制

1. **本地 Embedding 只是词面哈希**，能保证中文检索有结果、分数可排序，但不具备
   真正的语义泛化能力；正式环境请配置 DashScope/OpenAI Key。
2. **启发式分析**按关键词段落/句频抽取，质量明显低于大模型；LLM 生成内容也需
   人工核对原文（前端已有相应提示）。
3. **MinerU 未在本机安装**，代码按 `mineru -p <pdf> -o <dir>` 调用，未做端到端验证；
   如 CLI 参数不同，请调整 `parser.py::_try_mineru`。MinerU 耗时长，需同步调大
   后端 `research.ai.read-timeout-seconds`（默认 60 秒）。
4. **本机没有运行 Milvus**，已验证“初始化失败自动回退本地”；Milvus 真实读写路径
   尚未在本机做端到端验证。
5. **本机没有模型 Key**，真实 Qwen/OpenAI 调用路径未做在线验证；离线降级路径已完整测试。
6. **无 OCR**：扫描版 PDF 只能得到提示性文本块，需要 OCR/MinerU。
7. 表格、图片、公式只做文本级保留，未做结构化抽取。
8. 本地向量库是单进程文件存储，不适合多 worker 并发写；生产建议 Milvus。
9. 向量化失败时按契约返回 `indexed=0` 而不是抛错，后端会看到失败数量与原因，
   但不会自动重试；可通过重新解析或后续补偿任务重建索引。
10. `embedding_status` 只能更新已存在的 chunk 行；文本块的插入由 Java 后端负责。

---

## 12. 依赖

见 `pyproject.toml`：

```
fastapi / uvicorn / pymupdf / numpy / pymysql / pymilvus / httpx
pydantic-settings / python-dotenv
```

建议使用独立虚拟环境安装 `pyproject.toml` 中声明的依赖。
