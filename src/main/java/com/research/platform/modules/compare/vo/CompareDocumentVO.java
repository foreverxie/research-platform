package com.research.platform.modules.compare.vo;

import lombok.Data;

import java.util.List;

/**
 * 对比结果中的文献信息
 */
@Data
public class CompareDocumentVO {

    private Long id;

    private String title;

    private List<String> authors;

    private Integer publicationYear;

    private String journal;

    private String categoryName;

    private List<String> keywords;

    private String documentType;

    private String parseStatus;
}
