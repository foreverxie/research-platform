package com.research.platform.infrastructure.ai;

import java.util.Map;

/**
 * AI 任务执行结果
 *
 * @param success       是否成功
 * @param message       执行信息或失败原因
 * @param resultSummary 写入 ai_task.result_summary 的摘要信息
 */
public record AiTaskExecutionResult(
        boolean success,
        String message,
        Map<String, Object> resultSummary
) {

    public static AiTaskExecutionResult ok(String message) {
        return new AiTaskExecutionResult(true, message, null);
    }

    public static AiTaskExecutionResult ok(String message, Map<String, Object> summary) {
        return new AiTaskExecutionResult(true, message, summary);
    }

    public static AiTaskExecutionResult fail(String message) {
        return new AiTaskExecutionResult(false, message, null);
    }
}
