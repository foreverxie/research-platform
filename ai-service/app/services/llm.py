"""OpenAI 兼容的 Chat Completions 调用。

DashScope 使用 ``https://dashscope.aliyuncs.com/compatible-mode/v1``，
OpenAI 使用 ``OPENAI_BASE_URL``。未配置 Key 时 ``available=False``，
由上层走本地启发式/抽取式逻辑。
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass

import httpx

from ..config import Settings, get_settings

logger = logging.getLogger(__name__)


class LLMError(RuntimeError):
    """模型调用失败。"""


@dataclass(slots=True)
class ChatResult:
    text: str
    model: str
    prompt_tokens: int = 0
    completion_tokens: int = 0


class LLMService:
    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self.provider = self.settings.effective_llm_provider
        self.model = self.settings.effective_llm_model
        self._client: httpx.Client | None = None

        if self.available:
            logger.info("LLM 使用远程模型（provider=%s, model=%s）", self.provider, self.model)
        else:
            logger.info("LLM 未配置 Key，使用本地启发式模式（local-fallback）")

    @property
    def available(self) -> bool:
        return self.provider in ("dashscope", "openai") and bool(self.settings.llm_api_key)

    @property
    def model_name(self) -> str:
        return self.model if self.available else "local-fallback"

    def close(self) -> None:
        if self._client is not None:
            self._client.close()
            self._client = None

    @property
    def _http(self) -> httpx.Client:
        if self._client is None:
            self._client = httpx.Client(
                timeout=httpx.Timeout(self.settings.request_timeout_seconds, connect=10.0)
            )
        return self._client

    def chat(
        self,
        messages: list[dict[str, str]],
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> ChatResult:
        if not self.available:
            raise LLMError("未配置 LLM API Key")

        url = f"{self.settings.llm_base_url}/chat/completions"
        headers = {
            "Authorization": f"Bearer {self.settings.llm_api_key}",
            "Content-Type": "application/json",
        }
        payload: dict[str, object] = {
            "model": self.model,
            "messages": messages,
            "temperature": (
                self.settings.llm_temperature if temperature is None else float(temperature)
            ),
            "max_tokens": int(max_tokens or self.settings.llm_max_tokens),
        }

        response = self._post_with_retry(url, headers, payload)
        if response.status_code >= 400:
            body = response.text[:800]
            raise LLMError(f"LLM 接口返回 {response.status_code}：{body}")

        try:
            data = response.json()
            choice = (data.get("choices") or [])[0]
            content = choice.get("message", {}).get("content")
        except Exception as exc:  # noqa: BLE001
            raise LLMError(f"LLM 响应结构异常：{exc}") from exc

        if isinstance(content, list):
            content = "".join(
                str(part.get("text") or "") for part in content if isinstance(part, dict)
            )
        text = (content or "").strip()
        if not text:
            raise LLMError("LLM 返回了空内容")

        usage = data.get("usage") or {}
        return ChatResult(
            text=text,
            model=str(data.get("model") or self.model),
            prompt_tokens=int(usage.get("prompt_tokens") or usage.get("input_tokens") or 0),
            completion_tokens=int(
                usage.get("completion_tokens") or usage.get("output_tokens") or 0
            ),
        )

    def _post_with_retry(
        self,
        url: str,
        headers: dict[str, str],
        payload: dict[str, object],
    ) -> httpx.Response:
        last_error: Exception | None = None
        for attempt in range(2):
            try:
                response = self._http.post(url, headers=headers, json=payload)
            except httpx.HTTPError as exc:
                last_error = exc
                logger.warning("LLM 请求失败（第 %d 次）：%s", attempt + 1, exc)
                time.sleep(1.0 * (attempt + 1))
                continue

            if response.status_code in (429, 500, 502, 503, 504) and attempt == 0:
                logger.warning("LLM 接口返回 %s，准备重试", response.status_code)
                time.sleep(1.0)
                continue
            return response

        raise LLMError(f"LLM 请求失败：{last_error}")
