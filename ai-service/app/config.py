"""应用配置。

所有配置项都可以通过环境变量或项目根目录下的 ``.env`` 覆盖。
字段名遵循 pydantic-settings 的约定：环境变量不区分大小写，例如
``MYSQL_HOST`` 对应 ``mysql_host``。
"""

from __future__ import annotations

import logging
import shutil
from functools import lru_cache
from pathlib import Path

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

logger = logging.getLogger(__name__)

BASE_DIR = Path(__file__).resolve().parent.parent

DASHSCOPE_DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
OPENAI_DEFAULT_BASE_URL = "https://api.openai.com/v1"

VALID_EMBEDDING_PROVIDERS = {"auto", "dashscope", "openai", "local"}
VALID_LLM_PROVIDERS = {"auto", "dashscope", "openai", "local"}
VALID_VECTOR_STORES = {"milvus", "local"}
VALID_PARSERS = {"auto", "mineru", "pymupdf"}
VALID_MINERU_PROVIDERS = {"auto", "cloud", "local", "off"}


def _mineru_command_available(command: str) -> bool:
    """本地 MinerU CLI 是否可用（不执行，只查路径）。"""
    cmd = (command or "").strip()
    if not cmd:
        return False
    if shutil.which(cmd):
        return True
    return Path(cmd).is_file()


class Settings(BaseSettings):
    """服务配置。"""

    model_config = SettingsConfigDict(
        env_file=(BASE_DIR / ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # ------------------------------------------------------------------ 基础
    app_name: str = "ScholarAI AI Service"
    app_version: str = "1.0.0"
    app_port: int = 8000
    log_level: str = "INFO"

    # Java 后端只传相对 STORAGE_ROOT 的路径
    storage_root: Path = (BASE_DIR / ".." / "research-platform" / "storage").resolve()
    # 非空时所有 /internal/v1/* 接口校验 X-Internal-Token
    internal_token: str = ""
    # 调用模型接口的超时时间（秒）
    request_timeout_seconds: float = 60.0

    # ------------------------------------------------------------------ MySQL
    mysql_host: str = "localhost"
    mysql_port: int = 3306
    mysql_user: str = "root"
    mysql_password: str = ""
    mysql_database: str = "research_knowledge_db"
    mysql_connect_timeout: int = 5

    # --------------------------------------------------------------- Embedding
    # auto 时：有 DASHSCOPE_API_KEY -> dashscope；否则有 OPENAI_API_KEY -> openai；否则 local
    embedding_provider: str = "auto"
    embedding_model: str = "text-embedding-v3"
    embedding_dim: int = 1024
    embedding_batch_size: int = 10
    dashscope_api_key: str = ""
    openai_api_key: str = ""
    openai_base_url: str = ""
    # 一般不需要改；预留给私有化部署
    dashscope_base_url: str = DASHSCOPE_DEFAULT_BASE_URL

    # --------------------------------------------------------------------- LLM
    llm_provider: str = "auto"
    llm_model: str = "qwen-plus"
    llm_temperature: float = 0.2
    llm_max_tokens: int = 2000

    # -------------------------------------------------------------- 向量存储
    vector_store: str = "milvus"
    milvus_uri: str = "http://localhost:19530"
    milvus_collection: str = "paper_chunks"
    milvus_timeout_seconds: float = 5.0
    # 本地兜底向量库（numpy + JSON）
    local_vector_dir: Path = BASE_DIR / "data" / "vectors"

    # -------------------------------------------------------------------- 解析
    parser: str = "auto"
    mineru_cmd: str = "mineru"
    mineru_timeout_seconds: int = 600
    # MinerU 云端 API：auto 时配置了 MINERU_TOKEN 就优先走云端
    mineru_provider: str = "auto"
    mineru_token: str = ""
    mineru_model: str = "pipeline"
    mineru_ocr: bool = False
    mineru_cloud_base_url: str = "https://mineru.net/api/v4"
    mineru_cloud_timeout_seconds: int = 600

    # -------------------------------------------------------------------- 其他
    analyze_max_chars: int = 12000
    rag_max_context_chars: int = 8000

    # ------------------------------------------------------------------ 校验
    @field_validator("storage_root", "local_vector_dir", mode="after")
    @classmethod
    def _to_absolute_path(cls, value: Path) -> Path:
        path = Path(value).expanduser()
        if not path.is_absolute():
            path = (BASE_DIR / path).resolve()
        return path

    @field_validator("embedding_provider", mode="before")
    @classmethod
    def _normalize_embedding_provider(cls, value: object) -> str:
        text = str(value or "auto").strip().lower()
        if text not in VALID_EMBEDDING_PROVIDERS:
            raise ValueError(f"EMBEDDING_PROVIDER 必须是 {sorted(VALID_EMBEDDING_PROVIDERS)} 之一")
        return text

    @field_validator("llm_provider", mode="before")
    @classmethod
    def _normalize_llm_provider(cls, value: object) -> str:
        text = str(value or "auto").strip().lower()
        if text not in VALID_LLM_PROVIDERS:
            raise ValueError(f"LLM_PROVIDER 必须是 {sorted(VALID_LLM_PROVIDERS)} 之一")
        return text

    @field_validator("vector_store", mode="before")
    @classmethod
    def _normalize_vector_store(cls, value: object) -> str:
        text = str(value or "local").strip().lower()
        if text not in VALID_VECTOR_STORES:
            raise ValueError(f"VECTOR_STORE 必须是 {sorted(VALID_VECTOR_STORES)} 之一")
        return text

    @field_validator("parser", mode="before")
    @classmethod
    def _normalize_parser(cls, value: object) -> str:
        text = str(value or "auto").strip().lower()
        if text not in VALID_PARSERS:
            raise ValueError(f"PARSER 必须是 {sorted(VALID_PARSERS)} 之一")
        return text

    @field_validator("mineru_provider", mode="before")
    @classmethod
    def _normalize_mineru_provider(cls, value: object) -> str:
        text = str(value or "auto").strip().lower()
        if text not in VALID_MINERU_PROVIDERS:
            raise ValueError(f"MINERU_PROVIDER 必须是 {sorted(VALID_MINERU_PROVIDERS)} 之一")
        return text

    @field_validator("embedding_dim", mode="after")
    @classmethod
    def _check_dim(cls, value: int) -> int:
        if value <= 0 or value > 8192:
            raise ValueError("EMBEDDING_DIM 必须在 1~8192 之间")
        return value

    # -------------------------------------------------------------- 派生配置
    @property
    def has_dashscope_key(self) -> bool:
        return bool(self.dashscope_api_key.strip())

    @property
    def has_openai_key(self) -> bool:
        return bool(self.openai_api_key.strip())

    @property
    def effective_embedding_provider(self) -> str:
        """实际生效的 Embedding 提供方（local/dashscope/openai）。"""
        configured = self.embedding_provider
        if configured == "local":
            return "local"
        if configured == "dashscope":
            return "dashscope" if self.has_dashscope_key else "local"
        if configured == "openai":
            return "openai" if self.has_openai_key else "local"
        # auto
        if self.has_dashscope_key:
            return "dashscope"
        if self.has_openai_key:
            return "openai"
        return "local"

    @property
    def effective_llm_provider(self) -> str:
        """实际生效的 LLM 提供方（local/dashscope/openai）。"""
        configured = self.llm_provider
        if configured == "local":
            return "local"
        if configured == "dashscope":
            return "dashscope" if self.has_dashscope_key else "local"
        if configured == "openai":
            return "openai" if self.has_openai_key else "local"
        if self.has_dashscope_key:
            return "dashscope"
        if self.has_openai_key:
            return "openai"
        return "local"

    @property
    def effective_embedding_model(self) -> str:
        if self.effective_embedding_provider == "local":
            return "local-hash-v1"
        return self.embedding_model.strip() or "text-embedding-v3"

    @property
    def effective_llm_model(self) -> str:
        if self.effective_llm_provider == "local":
            return "local-fallback"
        return self.llm_model.strip() or "qwen-plus"

    @property
    def embedding_base_url(self) -> str:
        if self.effective_embedding_provider == "dashscope":
            return self.dashscope_base_url.rstrip("/")
        if self.effective_embedding_provider == "openai":
            return (self.openai_base_url.strip() or OPENAI_DEFAULT_BASE_URL).rstrip("/")
        return ""

    @property
    def llm_base_url(self) -> str:
        if self.effective_llm_provider == "dashscope":
            return self.dashscope_base_url.rstrip("/")
        if self.effective_llm_provider == "openai":
            return (self.openai_base_url.strip() or OPENAI_DEFAULT_BASE_URL).rstrip("/")
        return ""

    @property
    def embedding_api_key(self) -> str:
        if self.effective_embedding_provider == "dashscope":
            return self.dashscope_api_key.strip()
        if self.effective_embedding_provider == "openai":
            return self.openai_api_key.strip()
        return ""

    @property
    def llm_api_key(self) -> str:
        if self.effective_llm_provider == "dashscope":
            return self.dashscope_api_key.strip()
        if self.effective_llm_provider == "openai":
            return self.openai_api_key.strip()
        return ""

    @property
    def has_mineru_token(self) -> bool:
        return bool(self.mineru_token.strip())

    @property
    def effective_parser(self) -> str:
        """实际生效的解析器：mineru-cloud / mineru / pymupdf。"""
        if self.parser == "pymupdf":
            return "pymupdf"
        if self.mineru_provider in ("auto", "cloud") and self.has_mineru_token:
            return "mineru-cloud"
        if self.mineru_provider in ("auto", "local") and _mineru_command_available(self.mineru_cmd):
            return "mineru"
        return "pymupdf"

    def describe(self) -> str:
        """用于启动日志，绝不包含密钥明文。"""
        return (
            f"storage_root={self.storage_root} | mysql={self.mysql_host}:{self.mysql_port}/"
            f"{self.mysql_database} | parser={self.effective_parser} | "
            f"embedding={self.effective_embedding_provider}:{self.effective_embedding_model}"
            f"(dim={self.embedding_dim}) | "
            f"llm={self.effective_llm_provider}:{self.effective_llm_model} | "
            f"vector_store={self.vector_store}({self.milvus_uri}) | "
            f"token={'on' if self.internal_token else 'off'}"
        )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()


def reset_settings_cache() -> None:
    """测试辅助：清空配置缓存。"""
    get_settings.cache_clear()
