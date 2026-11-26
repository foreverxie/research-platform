package com.research.platform.modules.document.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文献文本块视图
 */
@Data
public class DocumentChunkVO {

    private Long id;

    private String chunkId;

    private Long userId;

    private Long documentId;

    private Long parseRecordId;

    private Integer chunkIndex;

    private String section;

    private Integer pageStart;

    private Integer pageEnd;

    private String content;

    private Integer tokenCount;

    private String embeddingStatus;

    private String embeddingModel;

    private LocalDateTime createdAt;
}
