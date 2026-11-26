package com.research.platform.modules.compare.vo;

import lombok.Data;

/**
 * 研究空白与潜在方向（AI 推断，需人工核对）
 */
@Data
public class CompareGapVO {

    private String title;

    private String description;

    /**
     * 推断依据，必须写明来源于哪些文献或哪个维度
     */
    private String basis;

    public CompareGapVO() {
    }

    public CompareGapVO(String title, String description, String basis) {
        this.title = title;
        this.description = description;
        this.basis = basis;
    }
}
