package com.research.platform.modules.category.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("category")
public class Category {

    /**
     * 分类ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID，NULL表示系统公共分类
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 分类名称
     */
    @TableField("name")
    private String name;

    /**
     * 父分类ID，0表示根分类
     */
    @TableField("parent_id")
    private Long parentId;

    /**
     * 分类描述
     */
    @TableField("description")
    private String description;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
