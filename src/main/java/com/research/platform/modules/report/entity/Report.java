package com.research.platform.modules.report.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "report", autoResultMap = true)
public class Report {

    /**
     * 报告ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 报告标题
     */
    @TableField("title")
    private String title;

    /**
     * 报告类型
     */
    @TableField("report_type")
    private String reportType;

    /**
     * 报告覆盖的文献ID列表
     */
    @TableField(value = "document_ids", typeHandler = JacksonTypeHandler.class)
    private List<Long> documentIds;

    /**
     * 报告状态
     */
    @TableField("status")
    private String status;

    /**
     * 报告摘要
     */
    @TableField("summary")
    private String summary;

    /**
     * Markdown 正文
     */
    @TableField("content")
    private String content;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除：0未删除，1已删除
     */
    @TableLogic(value = "0", delval = "1")
    @TableField("deleted")
    private Integer deleted;
}
