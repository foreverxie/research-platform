package com.research.platform.modules.analysis.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "ai_analysis", autoResultMap = true)
public class AiAnalysis {

    /**
     * 分析ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 文献ID
     */
    @TableField("document_id")
    private Long documentId;

    /**
     * AI任务ID
     */
    @TableField("task_id")
    private Long taskId;

    /**
     * 分析类型
     */
    @TableField("analysis_type")
    private String analysisType;

    /**
     * 分析状态
     */
    @TableField("status")
    private String status;

    /**
     * 分析版本
     */
    @TableField("version")
    private Integer version;

    /**
     * 是否当前有效版本：0否，1是
     */
    @TableField("is_current")
    private Integer isCurrent;

    /**
     * 摘要
     */
    @TableField("summary")
    private String summary;

    /**
     * 研究背景
     */
    @TableField("research_background")
    private String researchBackground;

    /**
     * 研究方法
     */
    @TableField("research_method")
    private String researchMethod;

    /**
     * 创新点列表
     */
    @TableField(value = "innovation_points", typeHandler = JacksonTypeHandler.class)
    private List<String> innovationPoints;

    /**
     * 实验结果
     */
    @TableField("experimental_results")
    private String experimentalResults;

    /**
     * 研究结论
     */
    @TableField("conclusion")
    private String conclusion;

    /**
     * 关键词列表
     */
    @TableField(value = "keywords", typeHandler = JacksonTypeHandler.class)
    private List<String> keywords;

    /**
     * 模型名称
     */
    @TableField("model_name")
    private String modelName;

    /**
     * 错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 完成时间
     */
    @TableField("completed_at")
    private LocalDateTime completedAt;
}
