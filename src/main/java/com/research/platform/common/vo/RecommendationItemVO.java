package com.research.platform.common.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 相似文献推荐项（跨模块共享）
 *
 * <p>文献详情的“相关文献”与推荐中心共用；同时返回推荐依据，
 * 便于用户判断推荐是否可信（FR-031）。</p>
 */
@Data
public class RecommendationItemVO {

    private Long documentId;

    private String title;

    private List<String> authors;

    private Integer publicationYear;

    private String journal;

    /**
     * 摘要；Java 关键字 abstract 不能作为字段名，序列化时仍输出 abstract
     */
    @JsonProperty("abstract")
    private String abstractText;

    private List<String> keywords;

    /**
     * 综合相似度，0-1
     */
    private Double score;

    /**
     * 推荐依据，例如“共享标签：Transformer；同属分类：自然语言处理”
     */
    private String reason;
}
