package com.research.platform.modules.analysis.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.common.vo.AnalysisVO;
import com.research.platform.modules.analysis.convert.AnalysisConvert;
import com.research.platform.modules.analysis.entity.AiAnalysis;
import com.research.platform.modules.analysis.mapper.AiAnalysisMapper;
import com.research.platform.modules.analysis.service.IAiAnalysisService;
import com.research.platform.modules.document.service.DocumentAccessService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * AI 分析查询服务实现
 *
 * <p>所有读取都先经过 {@link DocumentAccessService#requireOwned} 校验用户归属，
 * 避免仅凭文献 ID 读取他人分析结果。</p>
 */
@Service
public class AiAnalysisServiceImpl implements IAiAnalysisService {

    private final AiAnalysisMapper aiAnalysisMapper;
    private final DocumentAccessService documentAccessService;

    public AiAnalysisServiceImpl(
            AiAnalysisMapper aiAnalysisMapper,
            DocumentAccessService documentAccessService
    ) {
        this.aiAnalysisMapper = aiAnalysisMapper;
        this.documentAccessService = documentAccessService;
    }

    /**
     * 取当前有效版本
     */
    @Override
    public AiAnalysis current(Long documentId, String analysisType) {
        if (documentId == null) {
            throw new BusinessException(400, "文献ID不能为空");
        }
        if (!StringUtils.hasText(analysisType)) {
            throw new BusinessException(400, "分析类型不能为空");
        }

        documentAccessService.requireOwned(documentId);

        return aiAnalysisMapper.selectOne(new QueryWrapper<AiAnalysis>()
                .eq("document_id", documentId)
                .eq("analysis_type", analysisType.trim())
                .eq("is_current", 1)
                .last("LIMIT 1"));
    }

    /**
     * 取某文献的全部分析版本
     */
    @Override
    public List<AiAnalysis> listByDocument(Long documentId) {
        if (documentId == null) {
            throw new BusinessException(400, "文献ID不能为空");
        }

        documentAccessService.requireOwned(documentId);

        return aiAnalysisMapper.selectList(new QueryWrapper<AiAnalysis>()
                .eq("document_id", documentId)
                .orderByDesc("created_at")
                .orderByDesc("id"));
    }

    /**
     * 实体转视图
     */
    @Override
    public AnalysisVO toVO(AiAnalysis analysis) {
        return AnalysisConvert.toVO(analysis);
    }
}
