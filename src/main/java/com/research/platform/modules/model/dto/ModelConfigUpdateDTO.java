package com.research.platform.modules.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模型配置更新参数
 *
 * <p>字段均为可选：前端只提交需要修改的项，未提交的字段保持原值。
 * 密钥不在此处保存，只保存环境变量名。</p>
 */
@Data
public class ModelConfigUpdateDTO {

    @Size(max = 100, message = "模型名称长度不能超过100个字符")
    private String modelName;

    @Size(max = 100, message = "模型提供方长度不能超过100个字符")
    private String provider;

    @Size(max = 500, message = "API 地址长度不能超过500个字符")
    private String apiEndpoint;

    @Size(max = 100, message = "API Key 环境变量名长度不能超过100个字符")
    private String apiKeyEnvName;

    /**
     * Embedding 向量维度
     */
    @Min(value = 1, message = "向量维度必须大于0")
    @Max(value = 65536, message = "向量维度不能超过65536")
    private Integer dimension;

    /**
     * 状态：1可用，0停用
     */
    @Min(value = 0, message = "状态只能为0或1")
    @Max(value = 1, message = "状态只能为0或1")
    private Integer status;

    /**
     * 是否默认模型：1是，0否
     */
    @Min(value = 0, message = "默认标记只能为0或1")
    @Max(value = 1, message = "默认标记只能为0或1")
    private Integer isDefault;
}
