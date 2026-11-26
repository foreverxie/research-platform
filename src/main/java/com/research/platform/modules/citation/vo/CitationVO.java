package com.research.platform.modules.citation.vo;

import lombok.Data;

/**
 * 文献引用视图
 */
@Data
public class CitationVO {

    /**
     * 引用文本（GB/T 7714 格式）
     */
    private String text;

    /**
     * 引用格式，当前仅 gb7714
     */
    private String style;
}
