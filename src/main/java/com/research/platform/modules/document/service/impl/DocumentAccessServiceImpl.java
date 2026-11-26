package com.research.platform.modules.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.mapper.DocumentMapper;
import com.research.platform.modules.document.service.DocumentAccessService;
import com.research.platform.utils.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 文献归属校验实现
 *
 * <p>其他模块（笔记、分析、任务、问答等）统一通过本服务读取文献，
 * 避免越权访问。Document 使用逻辑删除，selectById 已自动过滤已删除记录。</p>
 */
@Service
public class DocumentAccessServiceImpl implements DocumentAccessService {

    private static final String NOT_FOUND_MESSAGE = "文献不存在或已被删除";

    private final DocumentMapper documentMapper;

    public DocumentAccessServiceImpl(DocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    @Override
    public Document requireOwned(Long documentId) {
        return requireOwned(CurrentUser.getCurrentUserId(), documentId);
    }

    @Override
    public Document requireOwned(Long userId, Long documentId) {
        if (userId == null || documentId == null) {
            throw new BusinessException(404, NOT_FOUND_MESSAGE);
        }

        Document document = documentMapper.selectById(documentId);

        if (document == null || !userId.equals(document.getUserId())) {
            // 不区分“不存在”与“他人文献”，避免泄露文献是否存在
            throw new BusinessException(404, NOT_FOUND_MESSAGE);
        }

        return document;
    }

    @Override
    public List<Document> listOwned(Long userId, Collection<Long> documentIds) {
        if (userId == null || documentIds == null || documentIds.isEmpty()) {
            return new ArrayList<>();
        }

        return documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId)
                .in(Document::getId, documentIds));
    }

    @Override
    public List<Document> listOwned(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }

        return documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId));
    }

    @Override
    public String titleOf(Long documentId) {
        if (documentId == null) {
            return null;
        }

        Document document = documentMapper.selectById(documentId);
        return document == null ? null : document.getTitle();
    }
}
