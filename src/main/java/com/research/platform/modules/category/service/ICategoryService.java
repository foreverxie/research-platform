package com.research.platform.modules.category.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.research.platform.modules.category.dto.CategoryCreateDTO;
import com.research.platform.modules.category.dto.CategoryUpdateDTO;
import com.research.platform.modules.category.entity.Category;
import com.research.platform.modules.category.vo.CategoryVO;

import java.util.List;

/**
 * 分类服务
 */
public interface ICategoryService extends IService<Category> {

    /**
     * 查询当前用户的分类树
     */
    List<CategoryVO> listTree();

    /**
     * 新建分类
     */
    CategoryVO create(CategoryCreateDTO dto);

    /**
     * 修改分类（改名、移动、描述）
     */
    CategoryVO update(Long id, CategoryUpdateDTO dto);

    /**
     * 删除分类（存在子分类或文献时拒绝）
     */
    void delete(Long id);
}
