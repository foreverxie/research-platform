"""内部服务鉴权：校验 X-Internal-Token。"""

from __future__ import annotations

import hmac
import logging

from fastapi import Header, HTTPException

from .config import get_settings

logger = logging.getLogger(__name__)


def verify_internal_token(
    x_internal_token: str | None = Header(default=None, alias="X-Internal-Token"),
) -> None:
    """当 ``INTERNAL_TOKEN`` 非空时要求请求头完全一致。

    Java 后端在 ``research.ai.internal-token`` 非空时会自动带上该请求头；
    为空时（默认）跳过校验，方便本地联调。
    """
    expected = get_settings().internal_token.strip()
    if not expected:
        return

    provided = (x_internal_token or "").strip()
    if not provided or not hmac.compare_digest(provided, expected):
        logger.warning("拒绝未通过内部令牌校验的请求")
        raise HTTPException(status_code=401, detail="无效的内部服务令牌")
