package com.research.platform.modules.annotation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 批注创建参数
 */
@Data
public class AnnotationCreateDTO {

    /**
     * 文献ID（必填）
     */
    @NotNull(message = "文献ID不能为空")
    private Long documentId;

    /**
     * 逻辑引用文本块标识，可为空
     */
    @Size(max = 64, message = "文本块标识长度不能超过64个字符")
    private String chunkId;

    /**
     * 页码
     */
    @Min(value = 1, message = "页码必须大于0")
    private Integer pageNumber;

    /**
     * 被批注的原文片段，可为空
     */
    @Size(max = 2000, message = "引用原文长度不能超过2000个字符")
    private String quote;

    /**
     * 批注内容（必填）
     */
    @NotBlank(message = "批注内容不能为空")
    @Size(max = 5000, message = "批注内容长度不能超过5000个字符")
    private String content;

    /**
     * 批注颜色，缺省为 #e6a23c
     */
    @Size(max = 20, message = "批注颜色长度不能超过20个字符")
    private String color;
}
