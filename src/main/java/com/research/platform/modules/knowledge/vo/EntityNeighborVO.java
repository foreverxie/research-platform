package com.research.platform.modules.knowledge.vo;

import lombok.Data;

/**
 * 实体的一跳邻居：邻居实体与连接关系
 */
@Data
public class EntityNeighborVO {

    private EntityVO entity;

    private RelationVO relation;
}
