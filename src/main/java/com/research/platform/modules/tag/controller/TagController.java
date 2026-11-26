package com.research.platform.modules.tag.controller;

import com.research.platform.common.result.Result;
import com.research.platform.common.vo.TagVO;
import com.research.platform.modules.tag.dto.TagCreateDTO;
import com.research.platform.modules.tag.dto.TagUpdateDTO;
import com.research.platform.modules.tag.service.ITagService;
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
 * 标签管理接口
 */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final ITagService tagService;

    public TagController(ITagService tagService) {
        this.tagService = tagService;
    }

    /**
     * 标签列表（含文献数量）
     */
    @GetMapping
    public Result<List<TagVO>> list() {
        return Result.success(tagService.listWithDocumentCount());
    }

    /**
     * 新建标签
     */
    @PostMapping
    public Result<TagVO> create(@Valid @RequestBody TagCreateDTO dto) {
        return Result.success(tagService.create(dto));
    }

    /**
     * 修改标签
     */
    @PutMapping("/{id}")
    public Result<TagVO> update(
            @PathVariable Long id,
            @Valid @RequestBody TagUpdateDTO dto
    ) {
        return Result.success(tagService.update(id, dto));
    }

    /**
     * 删除标签（同时解除文献关联）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        tagService.delete(id);
        return Result.success(null);
    }
}
