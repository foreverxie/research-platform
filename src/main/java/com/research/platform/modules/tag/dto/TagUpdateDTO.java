package com.research.platform.modules.tag.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 标签修改参数
 *
 * <p>字段为空表示不修改。</p>
 */
@Data
public class TagUpdateDTO {

    /**
     * 标签名称
     */
    @Size(max = 100, message = "标签名称长度不能超过100个字符")
    private String name;

    /**
     * 标签颜色
     */
    @Size(max = 20, message = "标签颜色长度不能超过20个字符")
    private String color;
}
