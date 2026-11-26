package com.research.platform.common.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 分析结果视图（跨模块共享）
 *
 * <p>包含版本信息：同一文献同一分析类型只允许一个 isCurrent = 1 的版本。</p>
 */
@Data
public class AnalysisVO {

    private Long id;

    private Long documentId;

    private Long taskId;

    private String analysisType;

    private String status;

    private Integer version;

    private Integer isCurrent;

    private String summary;

    private String researchBackground;

    private String researchMethod;

    private List<String> innovationPoints;

    private String experimentalResults;

    private String conclusion;

    private List<String> keywords;

    private String modelName;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;
}
