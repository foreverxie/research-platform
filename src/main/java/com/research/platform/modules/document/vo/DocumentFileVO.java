package com.research.platform.modules.document.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文献文件视图
 */
@Data
public class DocumentFileVO {

    private Long id;

    private Long documentId;

    private String fileType;

    private String fileName;

    private String storagePath;

    private Long fileSize;

    private String fileHash;

    private Integer version;

    private LocalDateTime createdAt;
}
