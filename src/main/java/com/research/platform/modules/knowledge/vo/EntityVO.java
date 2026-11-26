package com.research.platform.modules.knowledge.vo;

import lombok.Data;

/**
 * 知识实体视图（图谱节点）
 */
@Data
public class EntityVO {

    private Long id;

    private String name;

    /**
     * PAPER / AUTHOR / TOPIC / METHOD / MODEL / DATASET / INSTITUTION
     */
    private String type;

    private Long documentId;

    /**
     * 图谱权重，用于节点大小
     */
    private Integer weight;

    private String description;
}
