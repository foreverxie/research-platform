package com.research.platform.modules.annotation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文献批注（FR-041）
 */
@Data
@TableName("annotation")
public class Annotation {

    /**
     * 批注ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

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
     * 所属解析版本，可为空
     */
    @TableField("parse_record_id")
    private Long parseRecordId;

    /**
     * 逻辑引用 document_chunk.chunk_id
     */
    @TableField("chunk_id")
    private String chunkId;

    /**
     * 页码
     */
    @TableField("page_number")
    private Integer pageNumber;

    /**
     * 被批注的原文片段
     */
    @TableField("quote")
    private String quote;

    /**
     * 批注内容
     */
    @TableField("content")
    private String content;

    /**
     * 批注颜色
     */
    @TableField("color")
    private String color;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除：0未删除，1已删除
     */
    @TableLogic(value = "0", delval = "1")
    @TableField("deleted")
    private Integer deleted;
}
