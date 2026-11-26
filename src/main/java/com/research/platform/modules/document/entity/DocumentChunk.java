package com.research.platform.modules.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document_chunk")
public class DocumentChunk {

    /**
     * 数据库主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 文本块业务标识
     */
    @TableField("chunk_id")
    private String chunkId;

    /**
     * 所属用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 文献ID
     */
    @TableField("document_id")
    private Long documentId;

    /**
     * 解析版本ID
     */
    @TableField("parse_record_id")
    private Long parseRecordId;

    /**
     * 文本块顺序
     */
    @TableField("chunk_index")
    private Integer chunkIndex;

    /**
     * 所属章节
     */
    @TableField("section")
    private String section;

    /**
     * 起始页码
     */
    @TableField("page_start")
    private Integer pageStart;

    /**
     * 结束页码
     */
    @TableField("page_end")
    private Integer pageEnd;

    /**
     * 文本块原文
     */
    @TableField("content")
    private String content;

    /**
     * Token数量
     */
    @TableField("token_count")
    private Integer tokenCount;

    /**
     * 向量化状态
     */
    @TableField("embedding_status")
    private String embeddingStatus;

    /**
     * Embedding模型名称
     */
    @TableField("embedding_model")
    private String embeddingModel;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
