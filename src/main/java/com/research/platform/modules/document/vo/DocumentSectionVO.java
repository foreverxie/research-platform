package com.research.platform.modules.document.vo;

import lombok.Data;

/**
 * 阅读器章节视图
 *
 * <p>由同一解析版本中连续 section 相同的文本块合并而成。</p>
 */
@Data
public class DocumentSectionVO {

    private String section;

    private Integer pageStart;

    private Integer pageEnd;

    /**
     * 合并后的 Markdown 正文
     */
    private String markdown;
}
