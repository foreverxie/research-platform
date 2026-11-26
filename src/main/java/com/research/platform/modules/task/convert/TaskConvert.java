package com.research.platform.modules.task.convert;

import com.research.platform.common.vo.TaskVO;
import com.research.platform.modules.task.entity.AiTask;
import com.research.platform.modules.task.entity.AiTaskLog;
import com.research.platform.modules.task.vo.TaskLogVO;

/**
 * AI 任务对象转换
 */
public final class TaskConvert {

    private TaskConvert() {
    }

    /**
     * 任务实体转视图（documentTitle 由调用方批量补全）
     */
    public static TaskVO toVO(AiTask task) {
        if (task == null) {
            return null;
        }

        TaskVO vo = new TaskVO();
        vo.setId(task.getId());
        vo.setTaskNo(task.getTaskNo());
        vo.setDocumentId(task.getDocumentId());
        vo.setTaskType(task.getTaskType());
        vo.setStatus(task.getStatus());
        vo.setProgress(task.getProgress());
        vo.setCurrentStage(task.getCurrentStage());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setRetryCount(task.getRetryCount());
        vo.setCreatedAt(task.getCreatedAt());
        vo.setStartedAt(task.getStartedAt());
        vo.setCompletedAt(task.getCompletedAt());
        return vo;
    }

    /**
     * 任务日志实体转视图
     */
    public static TaskLogVO toLogVO(AiTaskLog taskLog) {
        if (taskLog == null) {
            return null;
        }

        TaskLogVO vo = new TaskLogVO();
        vo.setId(taskLog.getId());
        vo.setTaskId(taskLog.getTaskId());
        vo.setStage(taskLog.getStage());
        vo.setStatus(taskLog.getStatus());
        vo.setMessage(taskLog.getMessage());
        vo.setDurationMs(taskLog.getDurationMs());
        vo.setCreatedAt(taskLog.getCreatedAt());
        return vo;
    }
}
