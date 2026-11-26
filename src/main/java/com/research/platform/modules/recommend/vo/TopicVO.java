package com.research.platform.modules.recommend.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 主题推荐（基于文献关键词聚合）
 */
@Data
public class TopicVO {

    /**
     * 主题名称，即出现频次较高的文献关键词
     */
    private String topic;

    /**
     * 主题匹配度，0-1
     */
    private Double score;

    /**
     * 推荐理由
     */
    private String reason;

    /**
     * 与该主题共同出现的其它关键词（最多 5 个）
     */
    private List<String> relatedKeywords = new ArrayList<>();

    /**
     * 涉及该主题的文献ID
     */
    private List<Long> documentIds = new ArrayList<>();
}
