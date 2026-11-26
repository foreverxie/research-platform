package com.research.platform.common.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 标签视图（跨模块共享）
 *
 * <p>文献详情、标签管理、检索结果都会返回标签，字段统一在这里定义。</p>
 */
@Data
public class TagVO {

    private Long id;

    private String name;

    private String color;

    /**
     * 该标签关联的文献数量
     */
    private Long documentCount;

    private LocalDateTime createdAt;
}
