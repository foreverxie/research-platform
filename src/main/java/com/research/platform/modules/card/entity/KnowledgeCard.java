package com.research.platform.modules.card.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "knowledge_card", autoResultMap = true)
public class KnowledgeCard {

    /**
     * 卡片ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 卡片标题
     */
    @TableField("title")
    private String title;

    /**
     * 卡片内容
     */
    @TableField("content")
    private String content;

    /**
     * 卡片类型
     */
    @TableField("card_type")
    private String cardType;

    /**
     * 关联文献ID列表
     */
    @TableField(value = "document_ids", typeHandler = JacksonTypeHandler.class)
    private List<Long> documentIds;

    /**
     * 关联标签名称列表
     */
    @TableField(value = "tag_names", typeHandler = JacksonTypeHandler.class)
    private List<String> tagNames;

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
