package com.research.platform.modules.search.vo;

import lombok.Data;

/**
 * 命中的文本块（语义/混合检索）
 */
@Data
public class SearchChunkVO {

    private String chunkId;

    private Long documentId;

    private String section;

    private Integer pageStart;

    private Integer pageEnd;

    private String content;

    /**
     * 文本块相似度，0-1
     */
    private Double score;
}
