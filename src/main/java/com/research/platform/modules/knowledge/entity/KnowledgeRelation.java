package com.research.platform.modules.knowledge.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_relation")
public class KnowledgeRelation {

    /**
     * 关系ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 源实体ID
     */
    @TableField("source_id")
    private Long sourceId;

    /**
     * 目标实体ID
     */
    @TableField("target_id")
    private Long targetId;

    /**
     * 关系类型
     */
    @TableField("relation_type")
    private String relationType;

    /**
     * 关系置信度
     */
    @TableField("confidence")
    private Double confidence;

    /**
     * 来源依据
     */
    @TableField("evidence")
    private String evidence;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
