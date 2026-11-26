package com.research.platform.modules.knowledge.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识图谱统计
 */
@Data
public class KnowledgeStatsVO {

    private Integer entityCount;

    private Integer relationCount;

    private List<TypeCountVO> byType = new ArrayList<>();
}
