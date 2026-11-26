package com.research.platform.modules.task.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.research.platform.common.enums.TaskType;
import com.research.platform.modules.task.entity.AiTask;

import java.util.Map;

/**
 * AI 任务服务（跨模块契约）
 *
 * <p>文献模块通过 {@link #createTask} 发起解析/分析任务，任务由
 * aiTaskExecutor 异步执行，状态与日志由任务模块统一维护。</p>
 */
public interface ITaskService extends IService<AiTask> {

    /**
     * 创建任务并在事务提交后异步执行
     *
     * @param userId        任务所属用户
     * @param documentId    关联文献，可为空
     * @param taskType      任务类型
     * @param requestParams 请求参数（写入 ai_task.request_params）
     * @return 已落库的任务
     */
    AiTask createTask(Long userId, Long documentId, TaskType taskType, Map<String, Object> requestParams);

    /**
     * 更新执行进度（仅处理中的任务生效）
     *
     * @param taskId  任务ID
     * @param progress 1-99 的进度
     * @param stage    当前阶段说明
     */
    void updateProgress(Long taskId, int progress, String stage);

    /**
     * 提交任务到异步执行器
     */
    void submit(Long taskId);

    /**
     * 重试失败或已取消的任务（复用同一条任务记录）
     */
    AiTask retry(Long taskId);

    /**
     * 取消待处理或处理中的任务
     */
    AiTask cancel(Long taskId);
}
