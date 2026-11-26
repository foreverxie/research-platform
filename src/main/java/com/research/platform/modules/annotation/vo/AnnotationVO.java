package com.research.platform.modules.annotation.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文献批注视图
 */
@Data
public class AnnotationVO {

    private Long id;

    private Long documentId;

    private String documentTitle;

    private String chunkId;

    private Integer pageNumber;

    private String quote;

    private String content;

    private String color;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
