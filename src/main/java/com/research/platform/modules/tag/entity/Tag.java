package com.research.platform.modules.tag.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tag")
public class Tag {

    /**
     * 标签ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 标签名称
     */
    @TableField("name")
    private String name;

    /**
     * 标签颜色
     */
    @TableField("color")
    private String color;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
