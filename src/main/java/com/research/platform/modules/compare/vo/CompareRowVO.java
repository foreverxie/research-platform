package com.research.platform.modules.compare.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 对比维度行
 */
@Data
public class CompareRowVO {

    /**
     * 维度键：researchProblem / method / model / dataset / result / contribution / limitation
     */
    private String key;

    private String label;

    /**
     * 与 documents 顺序一一对应的取值
     */
    private List<String> values = new ArrayList<>();

    /**
     * 数据来源：ORIGINAL（原文）/ AI（AI 归纳）/ UNKNOWN（原文未明确陈述）
     */
    private String source;

    public CompareRowVO() {
    }

    public CompareRowVO(String key, String label, List<String> values, String source) {
        this.key = key;
        this.label = label;
        this.values = values;
        this.source = source;
    }
}
