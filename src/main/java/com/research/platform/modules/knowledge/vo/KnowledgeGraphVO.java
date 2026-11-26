package com.research.platform.modules.knowledge.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识图谱视图
 */
@Data
public class KnowledgeGraphVO {

    private List<EntityVO> entities = new ArrayList<>();

    private List<RelationVO> relations = new ArrayList<>();

    private KnowledgeStatsVO stats = new KnowledgeStatsVO();
}
