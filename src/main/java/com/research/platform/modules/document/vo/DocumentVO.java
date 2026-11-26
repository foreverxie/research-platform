package com.research.platform.modules.document.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.research.platform.common.vo.TagVO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文献列表项 / 卡片使用的精简视图
 *
 * <p>字段名与前端 {@code DocumentItem} 保持一致；摘要字段对外仍叫
 * abstract（Java 关键字不能作为字段名）。</p>
 */
@Data
public class DocumentVO {

    private Long id;

    /**
     * 所属用户ID
     */
    private Long userId;

    private String title;

    private List<String> authors;

    @JsonProperty("abstract")
    private String abstractText;

    private List<String> keywords;

    private Integer publicationYear;

    private String journal;

    private String doi;

    private String language;

    private String documentType;

    private Long categoryId;

    private String categoryName;

    private String source;

    private String parseStatus;

    /**
     * 当前有效解析记录ID；前端据此判断是否已有可用正文/是否允许 AI 分析
     */
    private Long currentParseRecordId;

    /**
     * 原始 PDF 大小（字节）
     */
    private Long fileSize;

    /**
     * 当前解析版本页数
     */
    private Integer pageCount;

    /**
     * 当前用户是否已收藏
     */
    private Boolean favorite;

    private List<TagVO> tags;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
