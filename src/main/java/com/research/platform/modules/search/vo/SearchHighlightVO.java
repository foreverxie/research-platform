package com.research.platform.modules.search.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 命中的字段片段
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchHighlightVO {

    /**
     * 字段名：标题 / 摘要 / 关键词 / 作者
     */
    private String field;

    /**
     * 字段内容片段
     */
    private String text;

}
