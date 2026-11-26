package com.research.platform.modules.report.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分析报告视图
 */
@Data
public class ReportVO {

    private Long id;

    private Long userId;

    private String title;

    /**
     * LITERATURE_ANALYSIS / TOPIC_REVIEW / COMPARE / CUSTOM
     */
    private String reportType;

    private List<Long> documentIds;

    /**
     * 关联文献标题，读取时实时补齐
     */
    private List<String> documentTitles;

    /**
     * DRAFT / COMPLETED
     */
    private String status;

    private String summary;

    /**
     * Markdown 正文
     */
    private String content;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
