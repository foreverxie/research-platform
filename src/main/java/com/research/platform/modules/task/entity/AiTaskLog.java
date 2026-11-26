package com.research.platform.modules.task.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_task_log")
public class AiTaskLog {

    /**
     * 日志ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 任务ID
     */
    @TableField("task_id")
    private Long taskId;

    /**
     * 执行阶段
     */
    @TableField("stage")
    private String stage;

    /**
     * 阶段状态
     */
    @TableField("status")
    private String status;

    /**
     * 执行信息
     */
    @TableField("message")
    private String message;

    /**
     * 执行耗时（毫秒）
     */
    @TableField("duration_ms")
    private Long durationMs;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
