package com.research.platform.modules.search.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 检索结果中的文献信息
 *
 * <p>只暴露检索页需要的字段，不直接复用文献模块的 DocumentVO，避免模块间耦合。</p>
 */
@Data
public class SearchDocumentVO {

    private Long id;

    private String title;

    private List<String> authors;

    /**
     * 摘要；Java 关键字 abstract 不能作为字段名，序列化时仍输出 abstract
     */
    @JsonProperty("abstract")
    private String abstractText;

    private List<String> keywords;

    private Integer publicationYear;

    private String journal;

    private String documentType;

    private Long categoryId;

    private String categoryName;

    private String parseStatus;

    private Long currentParseRecordId;
}
