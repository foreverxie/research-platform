package com.research.platform.modules.document.dto;

import com.research.platform.common.dto.PageQueryDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文献列表查询参数
 *
 * <p>与前端 {@code DocumentQuery} 一一对应；分页字段继承自
 * {@link PageQueryDTO}，排序字段只接受 {@code createdAt} /
 * {@code updatedAt} / {@code publicationYear} / {@code title}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DocumentQueryDTO extends PageQueryDTO {

    /**
     * 关键词：模糊匹配标题、摘要、关键词 JSON、作者 JSON
     */
    private String keyword;

    /**
     * 作者：匹配作者 JSON 文本
     */
    private String author;

    /**
     * 发表年份，精确匹配
     */
    private Integer year;

    /**
     * 标签ID，经由 document_tag 过滤
     */
    private Long tagId;

    /**
     * 分类ID，包含其全部子孙分类
     */
    private Long categoryId;

    /**
     * 是否只看收藏
     */
    private Boolean favorite;

    /**
     * 解析状态，精确匹配
     */
    private String parseStatus;

    /**
     * 排序方式，默认 createdAt
     */
    private String sort;
}
