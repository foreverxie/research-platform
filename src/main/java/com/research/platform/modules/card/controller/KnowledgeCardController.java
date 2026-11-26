package com.research.platform.modules.card.controller;

import com.research.platform.common.result.Result;
import com.research.platform.modules.card.dto.KnowledgeCardCreateDTO;
import com.research.platform.modules.card.dto.KnowledgeCardUpdateDTO;
import com.research.platform.modules.card.service.IKnowledgeCardService;
import com.research.platform.modules.card.vo.KnowledgeCardVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识卡片接口
 */
@RestController
@RequestMapping("/api/cards")
public class KnowledgeCardController {

    private final IKnowledgeCardService knowledgeCardService;

    public KnowledgeCardController(IKnowledgeCardService knowledgeCardService) {
        this.knowledgeCardService = knowledgeCardService;
    }

    /**
     * 知识卡片列表
     */
    @GetMapping
    public Result<List<KnowledgeCardVO>> list() {
        return Result.success(knowledgeCardService.listCards());
    }

    /**
     * 新建知识卡片
     */
    @PostMapping
    public Result<KnowledgeCardVO> create(@Valid @RequestBody KnowledgeCardCreateDTO dto) {
        return Result.success(knowledgeCardService.create(dto));
    }

    /**
     * 修改知识卡片
     */
    @PutMapping("/{id}")
    public Result<KnowledgeCardVO> update(
            @PathVariable Long id,
            @Valid @RequestBody KnowledgeCardUpdateDTO dto
    ) {
        return Result.success(knowledgeCardService.update(id, dto));
    }

    /**
     * 删除知识卡片（逻辑删除）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeCardService.delete(id);
        return Result.success(null);
    }
}
