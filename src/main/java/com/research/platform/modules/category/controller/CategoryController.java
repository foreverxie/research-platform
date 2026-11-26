package com.research.platform.modules.category.controller;

import com.research.platform.common.result.Result;
import com.research.platform.modules.category.dto.CategoryCreateDTO;
import com.research.platform.modules.category.dto.CategoryUpdateDTO;
import com.research.platform.modules.category.service.ICategoryService;
import com.research.platform.modules.category.vo.CategoryVO;
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
 * 分类管理接口
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final ICategoryService categoryService;

    public CategoryController(ICategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * 分类树（含子分类与直属文献数量）
     */
    @GetMapping
    public Result<List<CategoryVO>> listTree() {
        return Result.success(categoryService.listTree());
    }

    /**
     * 新建分类
     */
    @PostMapping
    public Result<CategoryVO> create(@Valid @RequestBody CategoryCreateDTO dto) {
        return Result.success(categoryService.create(dto));
    }

    /**
     * 修改分类（改名、移动、描述）
     */
    @PutMapping("/{id}")
    public Result<CategoryVO> update(
            @PathVariable Long id,
            @Valid @RequestBody CategoryUpdateDTO dto
    ) {
        return Result.success(categoryService.update(id, dto));
    }

    /**
     * 删除分类
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success(null);
    }
}
