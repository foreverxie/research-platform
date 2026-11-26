package com.research.platform.modules.chat.vo;

import lombok.Data;

/**
 * 问答引用视图（回答中引用的文献原文片段）
 */
@Data
public class ChatReferenceVO {

    private Long id;

    private Long messageId;

    private Long documentId;

    /**
     * 文献标题，由文献表补全
     */
    private String documentTitle;

    /**
     * 逻辑引用 document_chunk.chunk_id
     */
    private String chunkId;

    private Integer pageNumber;

    private String section;

    /**
     * 相关度得分，0-1
     */
    private Double relevanceScore;

    /**
     * 引用原文片段（按 chunkId 从文本块表读取前 160 字），读取不到为 null
     */
    private String snippet;
}
