package com.research.platform.modules.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型配置视图
 *
 * <p>字段与前端 {@code ModelConfig} 保持一致；不返回任何密钥明文。</p>
 */
@Data
public class ModelConfigVO {

    private Long id;

    private String modelName;

    private String modelType;

    private String provider;

    private String apiEndpoint;

    private String apiKeyEnvName;

    private Integer dimension;

    private Integer isDefault;

    private Integer status;

    private LocalDateTime createdAt;
}
