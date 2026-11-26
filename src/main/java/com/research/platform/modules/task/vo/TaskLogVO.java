package com.research.platform.modules.task.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 任务执行日志视图
 *
 * <p>字段与前端 {@code AiTaskLog} 保持一致。</p>
 */
@Data
public class TaskLogVO {

    private Long id;

    private Long taskId;

    /**
     * 执行阶段
     */
    private String stage;

    /**
     * 阶段状态，取值同任务状态
     */
    private String status;

    /**
     * 执行信息
     */
    private String message;

    /**
     * 执行耗时（毫秒）
     */
    private Long durationMs;

    private LocalDateTime createdAt;
}
