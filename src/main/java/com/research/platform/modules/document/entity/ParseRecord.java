package com.research.platform.modules.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("parse_record")
public class ParseRecord {

    /**
     * 解析记录ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 文献ID
     */
    @TableField("document_id")
    private Long documentId;

    /**
     * 关联AI任务ID
     */
    @TableField("task_id")
    private Long taskId;

    /**
     * 解析器名称
     */
    @TableField("parser_name")
    private String parserName;

    /**
     * 解析器版本
     */
    @TableField("parser_version")
    private String parserVersion;

    /**
     * 解析状态
     */
    @TableField("status")
    private String status;

    /**
     * Markdown结果文件ID
     */
    @TableField("markdown_file_id")
    private Long markdownFileId;

    /**
     * JSON结果文件ID
     */
    @TableField("json_file_id")
    private Long jsonFileId;

    /**
     * 页数
     */
    @TableField("page_count")
    private Integer pageCount;

    /**
     * 错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 是否当前有效版本：0否，1是
     */
    @TableField("is_current")
    private Integer isCurrent;

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
