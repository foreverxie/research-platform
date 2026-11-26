package com.research.platform.common.enums;

/**
 * AI 任务状态（对应 ai_task.status）
 */
public enum TaskStatus {

    /** 等待执行 */
    PENDING,

    /** 正在处理 */
    PROCESSING,

    /** 执行成功 */
    SUCCESS,

    /** 执行失败，可重试 */
    FAILED,

    /** 已取消 */
    CANCELLED;

    /**
     * 是否为终态（终态任务不再自动推进）
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }

    /**
     * 是否允许重试
     */
    public boolean isRetryable() {
        return this == FAILED || this == CANCELLED;
    }
}
