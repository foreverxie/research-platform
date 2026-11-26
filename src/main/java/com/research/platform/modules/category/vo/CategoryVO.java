package com.research.platform.modules.category.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分类视图（树形结构）
 */
@Data
public class CategoryVO {

    private Long id;

    private Long userId;

    private String name;

    private Long parentId;

    private String description;

    /**
     * 直属文献数量，不包含子分类下的文献
     */
    private Long documentCount;

    private LocalDateTime createdAt;

    /**
     * 子分类
     */
    private List<CategoryVO> children;
}
