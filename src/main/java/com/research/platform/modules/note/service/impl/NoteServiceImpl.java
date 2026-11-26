package com.research.platform.modules.note.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.common.result.PageResult;
import com.research.platform.common.vo.NoteVO;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.entity.DocumentChunk;
import com.research.platform.modules.document.mapper.DocumentChunkMapper;
import com.research.platform.modules.document.mapper.DocumentMapper;
import com.research.platform.modules.document.service.DocumentAccessService;
import com.research.platform.modules.note.dto.NoteCreateDTO;
import com.research.platform.modules.note.dto.NoteQueryDTO;
import com.research.platform.modules.note.dto.NoteUpdateDTO;
import com.research.platform.modules.note.entity.Note;
import com.research.platform.modules.note.mapper.NoteMapper;
import com.research.platform.modules.note.service.INoteService;
import com.research.platform.utils.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 笔记服务实现
 *
 * <p>列表按更新时间倒序；文件名/内容/关联文献标题均支持关键词模糊匹配。
 * 关联文献与文本块必须属于当前用户，跨用户访问一律按不存在处理。</p>
 */
@Service
public class NoteServiceImpl extends ServiceImpl<NoteMapper, Note> implements INoteService {

    private static final String DEFAULT_TITLE = "未命名笔记";

    private final NoteMapper noteMapper;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentAccessService documentAccessService;

    public NoteServiceImpl(
            NoteMapper noteMapper,
            DocumentMapper documentMapper,
            DocumentChunkMapper documentChunkMapper,
            DocumentAccessService documentAccessService
    ) {
        this.noteMapper = noteMapper;
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.documentAccessService = documentAccessService;
    }

    /**
     * 分页查询当前用户的笔记
     */
    @Override
    public PageResult<NoteVO> page(NoteQueryDTO query) {
        Long userId = CurrentUser.getCurrentUserId();
        String keyword = trimToNull(query.getKeyword());

        // 1. 关键词可能命中关联文献标题，先找出当前用户匹配的文献
        List<Long> matchedDocumentIds = new ArrayList<>();
        if (keyword != null) {
            List<Object> rawIds = documentMapper.selectObjs(
                    new QueryWrapper<Document>()
                            .select("id")
                            .eq("user_id", userId)
                            .like("title", keyword)
            );
            for (Object raw : rawIds) {
                if (raw instanceof Number) {
                    matchedDocumentIds.add(((Number) raw).longValue());
                }
            }
        }

        // 2. 组装查询条件
        QueryWrapper<Note> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        if (query.getDocumentId() != null) {
            wrapper.eq("document_id", query.getDocumentId());
        }
        if (keyword != null) {
            String finalKeyword = keyword;
            wrapper.and(w -> {
                w.like("title", finalKeyword).or().like("content", finalKeyword);
                if (!matchedDocumentIds.isEmpty()) {
                    w.or().in("document_id", matchedDocumentIds);
                }
            });
        }
        wrapper.orderByDesc("updated_at").orderByDesc("id");

        // 3. 分页查询
        Page<Note> page = noteMapper.selectPage(
                new Page<>(query.pageNo(), query.pageSize()),
                wrapper
        );

        // 4. 批量补齐关联文献标题
        Set<Long> documentIds = new LinkedHashSet<>();
        for (Note note : page.getRecords()) {
            if (note.getDocumentId() != null) {
                documentIds.add(note.getDocumentId());
            }
        }
        Map<Long, String> titleMap = resolveTitleMap(userId, documentIds);

        // 5. 转换为 VO 分页结果
        List<NoteVO> records = new ArrayList<>(page.getRecords().size());
        for (Note note : page.getRecords()) {
            records.add(toVO(note, titleMap.get(note.getDocumentId())));
        }
        Page<NoteVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(records);
        return PageResult.of(voPage);
    }

    /**
     * 新建笔记
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public NoteVO create(NoteCreateDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();

        Note note = new Note();
        note.setUserId(userId);
        note.setTitle(normalizeTitle(dto.getTitle()));
        note.setContent(dto.getContent() == null ? "" : dto.getContent());
        note.setPageNumber(dto.getPageNumber());

        // 1. 关联文献必须属于当前用户
        Document document = null;
        if (dto.getDocumentId() != null) {
            document = documentAccessService.requireOwned(dto.getDocumentId());
            note.setDocumentId(document.getId());
        }

        // 2. 文本块必须存在且属于当前用户/关联文献
        String chunkId = trimToNull(dto.getChunkId());
        if (chunkId != null) {
            validateChunk(userId, note.getDocumentId(), chunkId);
            note.setChunkId(chunkId);
        }

        // 3. 落库
        noteMapper.insert(note);
        Note saved = noteMapper.selectById(note.getId());
        return toVO(saved, document == null ? null : document.getTitle());
    }

    /**
     * 修改笔记
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public NoteVO update(Long id, NoteUpdateDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();

        // 1. 归属校验
        Note note = getOwnedNote(userId, id);

        // 2. 计算目标关联文献（字段未出现时保持原值）
        Long targetDocumentId = note.getDocumentId();
        if (dto.isDocumentIdPresent()) {
            targetDocumentId = dto.getDocumentId();
            if (targetDocumentId != null) {
                documentAccessService.requireOwned(targetDocumentId);
            }
        }

        // 3. 文本块校验（字段出现且非空时校验）
        String targetChunkId = note.getChunkId();
        if (dto.isChunkIdPresent()) {
            targetChunkId = trimToNull(dto.getChunkId());
            if (targetChunkId != null) {
                validateChunk(userId, targetDocumentId, targetChunkId);
            }
        }

        // 4. 更新（UpdateWrapper 支持把可空字段显式置为 NULL）
        UpdateWrapper<Note> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", note.getId()).eq("user_id", userId);

        if (dto.getTitle() != null) {
            wrapper.set("title", normalizeTitle(dto.getTitle()));
        }
        if (dto.getContent() != null) {
            wrapper.set("content", dto.getContent());
        }
        if (dto.isDocumentIdPresent()) {
            if (targetDocumentId == null) {
                wrapper.setSql("document_id = NULL");
            } else {
                wrapper.set("document_id", targetDocumentId);
            }
        }
        if (dto.isPageNumberPresent()) {
            if (dto.getPageNumber() == null) {
                wrapper.setSql("page_number = NULL");
            } else {
                wrapper.set("page_number", dto.getPageNumber());
            }
        }
        if (dto.isChunkIdPresent()) {
            if (targetChunkId == null) {
                wrapper.setSql("chunk_id = NULL");
            } else {
                wrapper.set("chunk_id", targetChunkId);
            }
        }
        wrapper.set("updated_at", LocalDateTime.now());
        noteMapper.update(null, wrapper);

        // 5. 返回最新数据
        Note saved = noteMapper.selectById(note.getId());
        Map<Long, String> titleMap = resolveTitleMap(
                userId,
                saved.getDocumentId() == null
                        ? Collections.emptyList()
                        : List.of(saved.getDocumentId())
        );
        return toVO(saved, titleMap.get(saved.getDocumentId()));
    }

    /**
     * 删除笔记（逻辑删除）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Long userId = CurrentUser.getCurrentUserId();
        Note note = getOwnedNote(userId, id);
        // @TableLogic 生成的删除语句自动更新 deleted = 1
        noteMapper.deleteById(note.getId());
    }

    /**
     * 查询当前用户拥有的笔记，找不到按 404 处理
     */
    private Note getOwnedNote(Long userId, Long id) {
        Note note = noteMapper.selectOne(
                new QueryWrapper<Note>()
                        .eq("id", id)
                        .eq("user_id", userId)
        );
        if (note == null) {
            throw new BusinessException(404, "笔记不存在");
        }
        return note;
    }

    /**
     * 校验文本块存在、属于当前用户，并且（若指定了文献）属于该文献
     */
    private void validateChunk(Long userId, Long documentId, String chunkId) {
        DocumentChunk chunk = documentChunkMapper.selectOne(
                new QueryWrapper<DocumentChunk>()
                        .eq("chunk_id", chunkId)
        );
        if (chunk == null || !Objects.equals(chunk.getUserId(), userId)) {
            throw new BusinessException(400, "关联的文本块不存在或无权访问");
        }
        if (documentId != null && !Objects.equals(chunk.getDocumentId(), documentId)) {
            throw new BusinessException(400, "关联的文本块不存在或无权访问");
        }
    }

    /**
     * 批量补齐文献标题（仅当前用户可访问的文献）
     */
    private Map<Long, String> resolveTitleMap(Long userId, Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> titleMap = new HashMap<>();
        for (Document document : documentAccessService.listOwned(userId, documentIds)) {
            if (document.getId() != null) {
                titleMap.put(document.getId(), document.getTitle());
            }
        }
        return titleMap;
    }

    /**
     * Entity 转 VO
     */
    private NoteVO toVO(Note note, String documentTitle) {
        if (note == null) {
            return null;
        }
        NoteVO vo = new NoteVO();
        vo.setId(note.getId());
        vo.setDocumentId(note.getDocumentId());
        vo.setDocumentTitle(documentTitle);
        vo.setTitle(note.getTitle());
        vo.setContent(note.getContent());
        vo.setPageNumber(note.getPageNumber());
        vo.setChunkId(note.getChunkId());
        vo.setCreatedAt(note.getCreatedAt());
        vo.setUpdatedAt(note.getUpdatedAt());
        return vo;
    }

    /**
     * 标题缺省值处理
     */
    private String normalizeTitle(String title) {
        if (title == null) {
            return DEFAULT_TITLE;
        }
        String trimmed = title.trim();
        return trimmed.isEmpty() ? DEFAULT_TITLE : trimmed;
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
