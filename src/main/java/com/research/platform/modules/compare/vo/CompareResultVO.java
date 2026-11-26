package com.research.platform.modules.compare.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 多篇文献对比结果
 */
@Data
public class CompareResultVO {

    private List<CompareDocumentVO> documents = new ArrayList<>();

    private List<CompareRowVO> rows = new ArrayList<>();

    /**
     * 研究空白与潜在方向，无法生成时为空数组
     */
    private List<CompareGapVO> gaps = new ArrayList<>();
}
