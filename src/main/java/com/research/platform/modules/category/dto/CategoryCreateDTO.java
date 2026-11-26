package com.research.platform.modules.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 分类创建参数
 */
@Data
public class CategoryCreateDTO {

    /**
     * 分类名称
     */
    @NotBlank(message = "分类名称不能为空")
    @Size(max = 100, message = "分类名称长度不能超过100个字符")
    private String name;

    /**
     * 父分类ID，0 表示根分类
     */
    private Long parentId = 0L;

    /**
     * 分类描述
     */
    @Size(max = 255, message = "分类描述长度不能超过255个字符")
    private String description;
}
