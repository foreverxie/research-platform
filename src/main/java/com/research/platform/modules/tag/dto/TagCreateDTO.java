package com.research.platform.modules.tag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 标签创建参数
 */
@Data
public class TagCreateDTO {

    /**
     * 标签名称
     */
    @NotBlank(message = "标签名称不能为空")
    @Size(max = 100, message = "标签名称长度不能超过100个字符")
    private String name;

    /**
     * 标签颜色，默认品牌蓝
     */
    @Size(max = 20, message = "标签颜色长度不能超过20个字符")
    private String color = "#3978e8";
}
