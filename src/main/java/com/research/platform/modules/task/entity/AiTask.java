package com.research.platform.modules.task.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "ai_task", autoResultMap = true)
public class AiTask {

    /**
     * 任务ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 业务任务编号
     */
    @TableField("task_no")
    private String taskNo;

    /**
     * 创建用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 关联文献ID，NULL表示非单篇文献任务
     */
    @TableField("document_id")
    private Long documentId;

    /**
     * 任务类型
     */
    @TableField("task_type")
    private String taskType;

    /**
     * 任务状态
     */
    @TableField("status")
    private String status;

    /**
     * 任务进度
     */
    @TableField("progress")
    private Integer progress;

    /**
     * 当前阶段
     */
    @TableField("current_stage")
    private String currentStage;

    /**
     * 请求参数
     */
    @TableField(value = "request_params", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> requestParams;

    /**
     * 结果摘要
     */
    @TableField(value = "result_summary", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> resultSummary;

    /**
     * 错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 重试次数
     */
    @TableField("retry_count")
    private Integer retryCount;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 开始时间
     */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    @TableField("completed_at")
    private LocalDateTime completedAt;
}
