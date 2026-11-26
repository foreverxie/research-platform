package com.research.platform.infrastructure.ai;

/**
 * AI 服务不可用或调用失败
 *
 * <p>该异常由全局异常处理器转换为 HTTP 业务码 503，
 * 前端据此提示“AI 服务暂不可用”，不影响其他业务操作。</p>
 */
public class AiServiceException extends RuntimeException {

    public AiServiceException(String message) {
        super(message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
