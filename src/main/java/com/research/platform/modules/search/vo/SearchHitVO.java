package com.research.platform.modules.search.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条检索结果
 */
@Data
public class SearchHitVO {

    private SearchDocumentVO document;

    /**
     * 相关度得分，0-1
     */
    private Double score;

    /**
     * 命中字段与片段，永不为 null
     */
    private List<SearchHighlightVO> highlights = new ArrayList<>();

    /**
     * 命中的文本块，keyword 模式为空
     */
    private List<SearchChunkVO> chunks = new ArrayList<>();
}
