package com.research.platform.modules.analysis.convert;

import com.research.platform.common.vo.AnalysisVO;
import com.research.platform.modules.analysis.entity.AiAnalysis;

import java.util.List;

/**
 * AI 分析结果对象转换
 */
public final class AnalysisConvert {

    private AnalysisConvert() {
    }

    /**
     * 分析实体转视图（公共类 AnalysisVO）
     */
    public static AnalysisVO toVO(AiAnalysis analysis) {
        if (analysis == null) {
            return null;
        }

        AnalysisVO vo = new AnalysisVO();
        vo.setId(analysis.getId());
        vo.setDocumentId(analysis.getDocumentId());
        vo.setTaskId(analysis.getTaskId());
        vo.setAnalysisType(analysis.getAnalysisType());
        vo.setStatus(analysis.getStatus());
        vo.setVersion(analysis.getVersion());
        vo.setIsCurrent(analysis.getIsCurrent());
        vo.setSummary(analysis.getSummary());
        vo.setResearchBackground(analysis.getResearchBackground());
        vo.setResearchMethod(analysis.getResearchMethod());
        vo.setInnovationPoints(defaultList(analysis.getInnovationPoints()));
        vo.setExperimentalResults(analysis.getExperimentalResults());
        vo.setConclusion(analysis.getConclusion());
        vo.setKeywords(defaultList(analysis.getKeywords()));
        vo.setModelName(analysis.getModelName());
        vo.setErrorMessage(analysis.getErrorMessage());
        vo.setCreatedAt(analysis.getCreatedAt());
        vo.setCompletedAt(analysis.getCompletedAt());
        return vo;
    }

    /**
     * JSON 字段可能为 NULL，前端按数组消费，这里统一兜底为空数组
     */
    private static List<String> defaultList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
