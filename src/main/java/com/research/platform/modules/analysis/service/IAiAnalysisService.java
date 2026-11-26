package com.research.platform.modules.analysis.service;

import com.research.platform.common.vo.AnalysisVO;
import com.research.platform.modules.analysis.entity.AiAnalysis;

import java.util.List;

/**
 * AI 分析查询服务
 *
 * <p>报告、文献对比等模块可以通过本接口读取当前有效的分析结果，
 * 无需重复实现“版本 + 用户归属”的过滤逻辑。</p>
 */
public interface IAiAnalysisService {

    /**
     * 取某文献某一分析类型的当前有效版本（is_current = 1）
     *
     * @return 不存在时返回 null
     */
    AiAnalysis current(Long documentId, String analysisType);

    /**
     * 取某文献的全部分析版本（按创建时间倒序）
     */
    List<AiAnalysis> listByDocument(Long documentId);

    /**
     * 分析实体转前端视图
     */
    AnalysisVO toVO(AiAnalysis analysis);
}
