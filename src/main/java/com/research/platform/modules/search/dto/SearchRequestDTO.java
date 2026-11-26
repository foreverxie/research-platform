package com.research.platform.modules.search.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 智能检索请求
 */
@Data
public class SearchRequestDTO {

    /**
     * 检索内容
     */
    @NotBlank(message = "检索内容不能为空")
    @Size(max = 500, message = "检索内容不能超过 500 个字符")
    private String query;

    /**
     * 检索模式：keyword / semantic / hybrid，默认 hybrid
     */
    private String mode;

    /**
     * 限定文献范围；为空表示个人文献库全部文献
     */
    private List<Long> documentIds;

    /**
     * 页码，从 1 开始
     */
    private Integer page;

    /**
     * 每页条数，上限 50
     */
    private Integer size;
}
