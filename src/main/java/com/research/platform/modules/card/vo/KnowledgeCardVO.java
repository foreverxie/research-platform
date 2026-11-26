package com.research.platform.modules.card.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识卡片视图
 */
@Data
public class KnowledgeCardVO {

    private Long id;

    private Long userId;

    private String title;

    private String content;

    /**
     * METHOD / MODEL / CONCEPT / CONCLUSION
     */
    private String cardType;

    private List<Long> documentIds;

    /**
     * 关联文献标题，读取时由 document 表实时补齐，不落库
     */
    private List<String> documentTitles;

    private List<String> tagNames;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
