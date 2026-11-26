package com.research.platform.infrastructure.ai;

import com.research.platform.common.enums.TaskType;
import com.research.platform.modules.task.entity.AiTask;

/**
 * AI 任务处理器
 *
 * <p>每个需要调用 AI 的任务类型（PDF 解析、AI 分析等）实现该接口，
 * 由 {@link AiTaskRunner} 统一调度、记录状态与日志。
 * 处理器只负责业务数据落库，不修改 ai_task 状态。</p>
 */
public interface AiTaskHandler {

    /**
     * 支持的任务类型
     */
    TaskType supports();

    /**
     * 执行任务
     *
     * @param task 任务记录
     * @return 执行结果
     */
    AiTaskExecutionResult handle(AiTask task);
}
