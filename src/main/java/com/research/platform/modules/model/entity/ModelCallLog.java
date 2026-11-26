package com.research.platform.modules.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("model_call_log")
public class ModelCallLog {

    /**
     * 日志ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联AI任务ID
     */
    @TableField("task_id")
    private Long taskId;

    /**
     * 模型名称
     */
    @TableField("model_name")
    private String modelName;

    /**
     * 模型类型
     */
    @TableField("model_type")
    private String modelType;

    /**
     * 请求Token数
     */
    @TableField("request_tokens")
    private Integer requestTokens;

    /**
     * 响应Token数
     */
    @TableField("response_tokens")
    private Integer responseTokens;

    /**
     * 调用耗时（毫秒）
     */
    @TableField("duration_ms")
    private Long durationMs;

    /**
     * 调用状态
     */
    @TableField("status")
    private String status;

    /**
     * 错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
