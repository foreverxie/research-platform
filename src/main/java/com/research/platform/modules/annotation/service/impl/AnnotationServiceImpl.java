package com.research.platform.modules.annotation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.modules.annotation.dto.AnnotationCreateDTO;
import com.research.platform.modules.annotation.dto.AnnotationUpdateDTO;
import com.research.platform.modules.annotation.entity.Annotation;
import com.research.platform.modules.annotation.mapper.AnnotationMapper;
import com.research.platform.modules.annotation.service.IAnnotationService;
import com.research.platform.modules.annotation.vo.AnnotationVO;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.entity.DocumentChunk;
import com.research.platform.modules.document.mapper.DocumentChunkMapper;
import com.research.platform.modules.document.service.DocumentAccessService;
import com.research.platform.utils.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 文献批注服务实现
 *
 * <p>批注必须挂在当前用户拥有的文献下；引用文本块时，文本块也必须属于当前用户与同一文献，
 * 跨用户访问一律按不存在处理（404 / 400），不泄露他人数据。</p>
 */
@Service
public class AnnotationServiceImpl
        extends ServiceImpl<AnnotationMapper, Annotation>
        implements IAnnotationService {

    /** 批注缺省颜色（与表默认值保持一致） */
    private static final String DEFAULT_COLOR = "#e6a23c";

    private final AnnotationMapper annotationMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentAccessService documentAccessService;

    public AnnotationServiceImpl(
            AnnotationMapper annotationMapper,
            DocumentChunkMapper documentChunkMapper,
            DocumentAccessService documentAccessService
    ) {
        this.annotationMapper = annotationMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.documentAccessService = documentAccessService;
    }

    /**
     * 查询某篇文献下的全部批注
     */
    @Override
    public List<AnnotationVO> listByDocument(Long documentId) {
        Long userId = CurrentUser.getCurrentUserId();
        // 1. 文献归属校验：不存在或不属于当前用户一律 404
        Document document = documentAccessService.requireOwned(documentId);

        // 2. 按创建时间倒序查询
        List<Annotation> annotations = annotationMapper.selectList(
                new QueryWrapper<Annotation>()
                        .eq("user_id", userId)
                        .eq("document_id", document.getId())
                        .orderByDesc("created_at")
                        .orderByDesc("id")
        );

        // 3. 转换为 VO，标题取当前文献标题
        List<AnnotationVO> result = new ArrayList<>(annotations.size());
        for (Annotation annotation : annotations) {
            result.add(toVO(annotation, document.getTitle()));
        }
        return result;
    }

    /**
     * 新建批注
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AnnotationVO create(AnnotationCreateDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();

        // 1. 文献必须属于当前用户
        Document document = documentAccessService.requireOwned(dto.getDocumentId());

        Annotation annotation = new Annotation();
        annotation.setUserId(userId);
        annotation.setDocumentId(document.getId());
        annotation.setPageNumber(dto.getPageNumber());
        annotation.setQuote(trimToNull(dto.getQuote()));
        annotation.setContent(dto.getContent().trim());
        annotation.setColor(normalizeColor(dto.getColor()));

        // 2. 文本块校验，并继承其解析版本
        String chunkId = trimToNull(dto.getChunkId());
        if (chunkId != null) {
            DocumentChunk chunk = requireOwnedChunk(userId, document.getId(), chunkId);
            annotation.setChunkId(chunkId);
            annotation.setParseRecordId(chunk.getParseRecordId());
        }

        // 3. 落库并返回
        annotationMapper.insert(annotation);
        Annotation saved = annotationMapper.selectById(annotation.getId());
        return toVO(saved, document.getTitle());
    }

    /**
     * 修改批注（只更新请求中出现的字段）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AnnotationVO update(Long id, AnnotationUpdateDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();
        Annotation annotation = getOwnedAnnotation(userId, id);

        UpdateWrapper<Annotation> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", annotation.getId()).eq("user_id", userId);

        if (dto.isQuotePresent()) {
            String quote = trimToNull(dto.getQuote());
            if (quote == null) {
                wrapper.setSql("quote = NULL");
            } else {
                wrapper.set("quote", quote);
            }
        }
        if (dto.isContentPresent()) {
            if (dto.getContent() == null || dto.getContent().trim().isEmpty()) {
                throw new BusinessException(400, "批注内容不能为空");
            }
            wrapper.set("content", dto.getContent().trim());
        }
        if (dto.isPageNumberPresent()) {
            if (dto.getPageNumber() == null) {
                wrapper.setSql("page_number = NULL");
            } else {
                wrapper.set("page_number", dto.getPageNumber());
            }
        }
        if (dto.isColorPresent()) {
            wrapper.set("color", normalizeColor(dto.getColor()));
        }
        wrapper.set("updated_at", LocalDateTime.now());

        annotationMapper.update(null, wrapper);

        Annotation saved = annotationMapper.selectById(annotation.getId());
        return toVO(saved, documentAccessService.titleOf(saved.getDocumentId()));
    }

    /**
     * 删除批注（逻辑删除）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Long userId = CurrentUser.getCurrentUserId();
        Annotation annotation = getOwnedAnnotation(userId, id);
        // @TableLogic 生成的删除语句自动更新 deleted = 1
        annotationMapper.deleteById(annotation.getId());
    }

    /**
     * 查询当前用户拥有的批注，找不到按 404 处理
     */
    private Annotation getOwnedAnnotation(Long userId, Long id) {
        Annotation annotation = annotationMapper.selectOne(
                new QueryWrapper<Annotation>()
                        .eq("id", id)
                        .eq("user_id", userId)
        );
        if (annotation == null) {
            throw new BusinessException(404, "批注不存在");
        }
        return annotation;
    }

    /**
     * 校验文本块存在、属于当前用户，并且属于指定文献
     */
    private DocumentChunk requireOwnedChunk(Long userId, Long documentId, String chunkId) {
        DocumentChunk chunk = documentChunkMapper.selectOne(
                new QueryWrapper<DocumentChunk>().eq("chunk_id", chunkId)
        );
        if (chunk == null
                || !Objects.equals(chunk.getUserId(), userId)
                || !Objects.equals(chunk.getDocumentId(), documentId)) {
            throw new BusinessException(400, "关联的文本块不存在或无权访问");
        }
        return chunk;
    }

    /**
     * Entity 转 VO
     */
    private AnnotationVO toVO(Annotation annotation, String documentTitle) {
        if (annotation == null) {
            return null;
        }
        AnnotationVO vo = new AnnotationVO();
        vo.setId(annotation.getId());
        vo.setDocumentId(annotation.getDocumentId());
        vo.setDocumentTitle(documentTitle);
        vo.setChunkId(annotation.getChunkId());
        vo.setPageNumber(annotation.getPageNumber());
        vo.setQuote(annotation.getQuote());
        vo.setContent(annotation.getContent());
        vo.setColor(annotation.getColor());
        vo.setCreatedAt(annotation.getCreatedAt());
        vo.setUpdatedAt(annotation.getUpdatedAt());
        return vo;
    }

    /**
     * 颜色缺省值处理
     */
    private String normalizeColor(String color) {
        String value = trimToNull(color);
        return value == null ? DEFAULT_COLOR : value;
    }

    /**
     * 去除首尾空白，空串按 null 处理
     */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
