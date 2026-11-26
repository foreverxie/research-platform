package com.research.platform.common.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 任务视图（跨模块共享）
 *
 * <p>文献详情的“最新任务”、任务中心、顶部栏角标都使用该结构。</p>
 */
@Data
public class TaskVO {

    private Long id;

    private String taskNo;

    private Long documentId;

    /**
     * 关联文献标题，便于前端直接展示
     */
    private String documentTitle;

    private String taskType;

    private String status;

    private Integer progress;

    private String currentStage;

    private String errorMessage;

    private Integer retryCount;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;
}
