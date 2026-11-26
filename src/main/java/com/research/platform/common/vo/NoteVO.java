package com.research.platform.common.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 科研笔记视图（跨模块共享）
 */
@Data
public class NoteVO {

    private Long id;

    private Long documentId;

    private String documentTitle;

    private String title;

    private String content;

    private Integer pageNumber;

    private String chunkId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
