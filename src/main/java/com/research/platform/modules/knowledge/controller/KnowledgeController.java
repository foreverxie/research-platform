package com.research.platform.modules.knowledge.controller;

import com.research.platform.common.result.Result;
import com.research.platform.modules.knowledge.service.IKnowledgeGraphService;
import com.research.platform.modules.knowledge.vo.EntityDetailVO;
import com.research.platform.modules.knowledge.vo.KnowledgeGraphVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识图谱接口
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final IKnowledgeGraphService knowledgeGraphService;


    /**
     * 知识图谱（实体 + 关系 + 类型统计）
     *
     * @param topic 主题过滤（实体名称/描述匹配，命中后带一跳邻居）
     * @param types 实体类型过滤，逗号分隔
     */
    @GetMapping("/graph")
    public Result<KnowledgeGraphVO> graph(
            @RequestParam(value = "topic", required = false) String topic,
            @RequestParam(value = "types", required = false) String types) {
        List<String> typeList = types == null || types.isBlank()
                ? List.of()
                : List.of(types.split(","));
        return Result.success(knowledgeGraphService.graph(topic, typeList));
    }

    /**
     * 实体详情与邻居节点
     */
    @GetMapping("/entities/{id}")
    public Result<EntityDetailVO> entityDetail(@PathVariable("id") Long id) {
        return Result.success(knowledgeGraphService.entityDetail(id));
    }
}
