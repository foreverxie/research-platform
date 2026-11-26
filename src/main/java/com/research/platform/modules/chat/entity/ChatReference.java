package com.research.platform.modules.chat.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_reference")
public class ChatReference {

    /**
     * 引用ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属消息ID
     */
    @TableField("message_id")
    private Long messageId;

    /**
     * 文献ID
     */
    @TableField("document_id")
    private Long documentId;

    /**
     * 逻辑引用document_chunk.chunk_id
     */
    @TableField("chunk_id")
    private String chunkId;

    /**
     * 页码
     */
    @TableField("page_number")
    private Integer pageNumber;

    /**
     * 章节
     */
    @TableField("section")
    private String section;

    /**
     * 相关度得分
     */
    @TableField("relevance_score")
    private Double relevanceScore;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
