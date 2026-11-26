package com.research.platform.modules.knowledge.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_entity")
public class KnowledgeEntity {

    /**
     * 实体ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 来源文献ID，主题类实体可为空
     */
    @TableField("document_id")
    private Long documentId;

    /**
     * 实体名称
     */
    @TableField("name")
    private String name;

    /**
     * 实体类型：PAPER/AUTHOR/TOPIC/METHOD/MODEL/DATASET/INSTITUTION
     */
    @TableField("type")
    private String type;

    /**
     * 图谱权重，用于节点大小
     */
    @TableField("weight")
    private Integer weight;

    /**
     * 实体描述
     */
    @TableField("description")
    private String description;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
