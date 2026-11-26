package com.research.platform.modules.knowledge.service;

import com.research.platform.modules.knowledge.vo.EntityDetailVO;
import com.research.platform.modules.knowledge.vo.KnowledgeGraphVO;

import java.util.List;

/**
 * 知识图谱服务
 */
public interface IKnowledgeGraphService {

    /**
     * 查询知识图谱，可按主题与实体类型过滤
     *
     * @param topic 主题关键词（匹配实体名称/描述）
     * @param types 实体类型过滤，为空表示不过滤
     */
    KnowledgeGraphVO graph(String topic, List<String> types);

    /**
     * 实体详情与一跳邻居
     */
    EntityDetailVO entityDetail(Long entityId);

    /**
     * 按元数据（文献/作者/关键词）幂等建图，保证无 AI 服务时也有可用图谱
     */
    void ensureMetadataGraph(Long userId);
}
