package com.research.platform.infrastructure.ai;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.research.platform.common.enums.TaskStatus;
import com.research.platform.common.enums.TaskType;
import com.research.platform.modules.task.entity.AiTask;
import com.research.platform.modules.task.entity.AiTaskLog;
import com.research.platform.modules.task.mapper.AiTaskLogMapper;
import com.research.platform.modules.task.mapper.AiTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 任务执行器
 *
 * <p>职责：把任务从 PENDING 推进到终态，并记录执行日志。
 * 具体业务（写解析记录、保存分析结果）由 {@link AiTaskHandler} 实现。</p>
 *
 * <p>任务处理器在运行时按需从容器中查找，避免
 * “任务服务 → 执行器 → 处理器 → 文献服务 → 任务服务”的构造器循环依赖。</p>
 */
@Component
public class AiTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(AiTaskRunner.class);

    private static final int MAX_ERROR_LENGTH = 2000;

    private final AiTaskMapper aiTaskMapper;
    private final AiTaskLogMapper aiTaskLogMapper;
    private final ApplicationContext applicationContext;

    public AiTaskRunner(
            AiTaskMapper aiTaskMapper,
            AiTaskLogMapper aiTaskLogMapper,
            ApplicationContext applicationContext
    ) {
        this.aiTaskMapper = aiTaskMapper;
        this.aiTaskLogMapper = aiTaskLogMapper;
        this.applicationContext = applicationContext;
    }

    /**
     * 异步执行任务
     *
     * @param taskId ai_task.id
     */
    @Async("aiTaskExecutor")
    public void run(Long taskId) {
        AiTask task = loadTask(taskId);
        if (task == null) {
            log.warn("AI 任务不存在：{}", taskId);
            return;
        }

        String status = task.getStatus();
        if (!TaskStatus.PENDING.name().equals(status) && !TaskStatus.PROCESSING.name().equals(status)) {
            return;
        }

        if (!markProcessing(task)) {
            log.info("任务状态已变化（可能已取消），跳过执行：{}", taskId);
            return;
        }

        long start = System.currentTimeMillis();

        try {
            TaskType taskType = TaskType.valueOf(task.getTaskType());
            AiTaskHandler handler = findHandler(taskType);

            if (handler == null) {
                markFailed(task, "暂不支持的任务类型：" + task.getTaskType(), 0);
                return;
            }

            AiTaskExecutionResult result = handler.handle(task);
            long duration = System.currentTimeMillis() - start;

            if (result != null && result.success()) {
                // 处理器执行期间任务可能已被取消，终态更新必须带状态条件
                if (markSuccess(task, result)) {
                    appendLog(task.getId(), "任务完成", TaskStatus.SUCCESS, result.message(), duration);
                }
            } else {
                markFailed(task, result == null ? "任务未返回结果" : result.message(), duration);
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("AI 任务执行失败：taskId={}, type={}", task.getId(), task.getTaskType(), e);
            markFailed(task, resolveMessage(e), duration);
        }
    }

    /**
     * 数据库事务提交存在延迟时，短暂重试读取任务
     */
    private AiTask loadTask(Long taskId) {
        for (int i = 0; i < 5; i++) {
            AiTask task = aiTaskMapper.selectById(taskId);
            if (task != null) {
                return task;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 按任务类型查找处理器（运行时查找，避免启动期循环依赖）
     */
    private AiTaskHandler findHandler(TaskType taskType) {
        Map<String, AiTaskHandler> handlerBeans =
                applicationContext.getBeansOfType(AiTaskHandler.class);

        for (AiTaskHandler handler : handlerBeans.values()) {
            if (handler.supports() == taskType) {
                return handler;
            }
        }

        return null;
    }

    /**
     * 进入处理中；只有仍处于 PENDING/PROCESSING 的任务才会被更新，
     * 避免覆盖用户刚刚提交的“取消”状态。
     */
    private boolean markProcessing(AiTask task) {
        int updated = aiTaskMapper.update(null, new LambdaUpdateWrapper<AiTask>()
                .eq(AiTask::getId, task.getId())
                .in(AiTask::getStatus, TaskStatus.PENDING.name(), TaskStatus.PROCESSING.name())
                .set(AiTask::getStatus, TaskStatus.PROCESSING.name())
                .set(AiTask::getStartedAt, LocalDateTime.now())
                .set(AiTask::getProgress, 5)
                .set(AiTask::getCurrentStage, "任务开始执行")
                .set(AiTask::getErrorMessage, null));

        if (updated == 0) {
            return false;
        }

        appendLog(task.getId(), "任务开始", TaskStatus.PROCESSING, "任务已进入执行队列", 0);
        return true;
    }

    /**
     * 标记成功；仅当任务仍为 PROCESSING 时生效，防止把已取消的任务改回成功
     */
    private boolean markSuccess(AiTask task, AiTaskExecutionResult result) {
        task.setStatus(TaskStatus.SUCCESS.name());
        task.setProgress(100);
        task.setCurrentStage("任务执行完成");
        task.setCompletedAt(LocalDateTime.now());
        task.setErrorMessage(null);

        if (result.resultSummary() != null) {
            task.setResultSummary(result.resultSummary());
        }

        int updated = aiTaskMapper.update(task, new LambdaUpdateWrapper<AiTask>()
                .eq(AiTask::getId, task.getId())
                .eq(AiTask::getStatus, TaskStatus.PROCESSING.name()));

        if (updated == 0) {
            appendLog(task.getId(), "忽略执行结果", TaskStatus.CANCELLED,
                    "任务已取消或已被其他流程处理，本次结果不覆盖任务状态", 0);
            return false;
        }

        return true;
    }

    /**
     * 标记失败；任务已被取消时不再覆盖其状态
     */
    private void markFailed(AiTask task, String message, long duration) {
        String error = truncate(message);

        task.setStatus(TaskStatus.FAILED.name());
        task.setCurrentStage("执行失败");
        task.setErrorMessage(error);
        task.setCompletedAt(LocalDateTime.now());

        int updated = aiTaskMapper.update(task, new LambdaUpdateWrapper<AiTask>()
                .eq(AiTask::getId, task.getId())
                .in(AiTask::getStatus, TaskStatus.PENDING.name(), TaskStatus.PROCESSING.name()));

        if (updated == 0) {
            appendLog(task.getId(), "忽略执行结果", TaskStatus.CANCELLED,
                    "任务已取消，失败结果不再覆盖任务状态", duration);
            return;
        }

        appendLog(task.getId(), "执行失败", TaskStatus.FAILED, error, duration);
    }

    private void appendLog(Long taskId, String stage, TaskStatus status, String message, long durationMs) {
        AiTaskLog taskLog = new AiTaskLog();
        taskLog.setTaskId(taskId);
        taskLog.setStage(stage);
        taskLog.setStatus(status.name());
        taskLog.setMessage(truncate(message));
        taskLog.setDurationMs(durationMs);
        aiTaskLogMapper.insert(taskLog);
    }

    private String resolveMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
