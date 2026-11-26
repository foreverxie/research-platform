package com.research.platform.modules.category.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 分类修改参数
 *
 * <p>字段为空表示不修改；description 需要支持「清空」，因此额外记录该字段是否出现在请求中。</p>
 */
@Data
public class CategoryUpdateDTO {

    /**
     * 分类名称
     */
    @Size(max = 100, message = "分类名称长度不能超过100个字符")
    private String name;

    /**
     * 父分类ID，0 表示根分类
     */
    private Long parentId;

    /**
     * 分类描述
     */
    @Size(max = 255, message = "分类描述长度不能超过255个字符")
    private String description;

    /**
     * 请求是否显式携带 description（用于区分「未传」与「传 null 清空」）
     */
    @JsonIgnore
    private boolean descriptionPresent;

    public void setDescription(String description) {
        this.description = description;
        this.descriptionPresent = true;
    }

    @JsonIgnore
    public boolean isDescriptionPresent() {
        return descriptionPresent;
    }
}
