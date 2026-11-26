package com.research.platform.modules.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "document", autoResultMap = true)
public class Document {

    /**
     * 文献ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 论文标题
     */
    @TableField("title")
    private String title;

    /**
     * 作者列表
     */
    @TableField(value = "authors", typeHandler = JacksonTypeHandler.class)
    private List<String> authors;

    /**
     * 论文摘要
     */
    @TableField("abstract")
    private String abstractText;

    /**
     * 关键词列表
     */
    @TableField(value = "keywords", typeHandler = JacksonTypeHandler.class)
    private List<String> keywords;

    /**
     * 发表年份
     */
    @TableField("publication_year")
    private Integer publicationYear;

    /**
     * 期刊或会议
     */
    @TableField("journal")
    private String journal;

    /**
     * DOI
     */
    @TableField("doi")
    private String doi;

    /**
     * 文献语言
     */
    @TableField("language")
    private String language;

    /**
     * 文献类型
     */
    @TableField("document_type")
    private String documentType;

    /**
     * 分类ID
     */
    @TableField("category_id")
    private Long categoryId;

    /**
     * 文献来源
     */
    @TableField("source")
    private String source;

    /**
     * 最近一次解析尝试状态
     */
    @TableField("parse_status")
    private String parseStatus;

    /**
     * 当前有效解析记录ID
     */
    @TableField("current_parse_record_id")
    private Long currentParseRecordId;

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
