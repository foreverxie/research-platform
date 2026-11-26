package com.research.platform.modules.note.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("note")
public class Note {

    /**
     * 笔记ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 关联文献ID
     */
    @TableField("document_id")
    private Long documentId;

    /**
     * 笔记标题
     */
    @TableField("title")
    private String title;

    /**
     * 笔记内容
     */
    @TableField("content")
    private String content;

    /**
     * 页码
     */
    @TableField("page_number")
    private Integer pageNumber;

    /**
     * 逻辑引用document_chunk.chunk_id
     */
    @TableField("chunk_id")
    private String chunkId;

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
