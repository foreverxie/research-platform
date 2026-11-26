package com.research.platform.modules.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("model_config")
public class ModelConfig {

    /**
     * 配置ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 模型名称
     */
    @TableField("model_name")
    private String modelName;

    /**
     * 模型类型：EMBEDDING/LLM
     */
    @TableField("model_type")
    private String modelType;

    /**
     * 模型提供方
     */
    @TableField("provider")
    private String provider;

    /**
     * API 地址
     */
    @TableField("api_endpoint")
    private String apiEndpoint;

    /**
     * API Key 环境变量名称
     */
    @TableField("api_key_env_name")
    private String apiKeyEnvName;

    /**
     * Embedding向量维度
     */
    @TableField("dimension")
    private Integer dimension;

    /**
     * 是否默认模型：0否，1是
     */
    @TableField("is_default")
    private Integer isDefault;

    /**
     * 状态：1可用，0禁用
     */
    @TableField("status")
    private Integer status;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
