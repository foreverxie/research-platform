package com.research.platform.modules.knowledge.vo;

import lombok.Data;

/**
 * 知识关系视图（图谱连线）
 */
@Data
public class RelationVO {

    private Long id;

    private Long sourceId;

    private Long targetId;

    /**
     * AUTHORED / STUDIES / USES_METHOD / USES_DATASET / CITES / AFFILIATED
     */
    private String relationType;

    private Double confidence;

    private String evidence;
}
