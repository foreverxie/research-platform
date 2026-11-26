package com.research.platform.modules.document.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 解析记录视图
 */
@Data
public class ParseRecordVO {

    private Long id;

    private Long documentId;

    private Long taskId;

    private String parserName;

    private String parserVersion;

    private String status;

    private Long markdownFileId;

    private Long jsonFileId;

    private Integer pageCount;

    private String errorMessage;

    /**
     * 是否当前有效版本：0 否，1 是
     */
    private Integer isCurrent;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;
}
