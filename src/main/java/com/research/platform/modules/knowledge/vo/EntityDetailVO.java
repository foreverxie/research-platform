package com.research.platform.modules.knowledge.vo;

import com.research.platform.modules.search.vo.SearchDocumentVO;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识实体详情：实体本身、一跳邻居与关联文献
 */
@Data
public class EntityDetailVO {

    private EntityVO entity;

    private List<EntityNeighborVO> neighbors = new ArrayList<>();

    /**
     * 关联文献（含实体自身来源文献与邻居实体的来源文献）
     */
    private List<SearchDocumentVO> documents = new ArrayList<>();
}
