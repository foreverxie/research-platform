package com.research.platform.modules.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.research.platform.common.enums.AnalysisType;
import com.research.platform.common.enums.ParseStatus;
import com.research.platform.common.enums.TaskStatus;
import com.research.platform.common.enums.TaskType;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.common.result.PageResult;
import com.research.platform.common.vo.AnalysisVO;
import com.research.platform.common.vo.NoteVO;
import com.research.platform.common.vo.RecommendationItemVO;
import com.research.platform.common.vo.TagVO;
import com.research.platform.common.vo.TaskVO;
import com.research.platform.infrastructure.storage.FileStorageService;
import com.research.platform.modules.analysis.entity.AiAnalysis;
import com.research.platform.modules.analysis.mapper.AiAnalysisMapper;
import com.research.platform.modules.category.entity.Category;
import com.research.platform.modules.category.mapper.CategoryMapper;
import com.research.platform.modules.document.convert.DocumentConvert;
import com.research.platform.modules.document.dto.AnalyzeRequestDTO;
import com.research.platform.modules.document.dto.DocumentQueryDTO;
import com.research.platform.modules.document.dto.DocumentUpdateDTO;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.entity.DocumentChunk;
import com.research.platform.modules.document.entity.DocumentFile;
import com.research.platform.modules.document.entity.DocumentTag;
import com.research.platform.modules.document.entity.ParseRecord;
import com.research.platform.modules.document.mapper.DocumentChunkMapper;
import com.research.platform.modules.document.mapper.DocumentFileMapper;
import com.research.platform.modules.document.mapper.DocumentMapper;
import com.research.platform.modules.document.mapper.DocumentTagMapper;
import com.research.platform.modules.document.mapper.ParseRecordMapper;
import com.research.platform.modules.document.service.DocumentAccessService;
import com.research.platform.modules.document.service.IDocumentService;
import com.research.platform.modules.document.vo.DocumentChunkVO;
import com.research.platform.modules.document.vo.DocumentContentVO;
import com.research.platform.modules.document.vo.DocumentDetailVO;
import com.research.platform.modules.document.vo.DocumentFileVO;
import com.research.platform.modules.document.vo.DocumentSectionVO;
import com.research.platform.modules.document.vo.DocumentVO;
import com.research.platform.modules.document.vo.ParseRecordVO;
import com.research.platform.modules.document.vo.ReparseResultVO;
import com.research.platform.modules.document.vo.UploadResultVO;
import com.research.platform.modules.favorite.entity.Favorite;
import com.research.platform.modules.favorite.mapper.FavoriteMapper;
import com.research.platform.modules.note.entity.Note;
import com.research.platform.modules.note.mapper.NoteMapper;
import com.research.platform.modules.tag.entity.Tag;
import com.research.platform.modules.tag.mapper.TagMapper;
import com.research.platform.modules.task.entity.AiTask;
import com.research.platform.modules.task.mapper.AiTaskMapper;
import com.research.platform.modules.task.service.ITaskService;
import com.research.platform.utils.CurrentUser;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文献服务实现
 *
 * <p>用户隔离：所有入口都从 {@link CurrentUser} 取用户ID，
 * 单篇访问统一走 {@link DocumentAccessService#requireOwned(Long)}。</p>
 */
@Service
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, Document> implements IDocumentService {

    /**
     * 单个 PDF 大小上限：50MB
     */
    private static final long MAX_PDF_SIZE = 50L * 1024 * 1024;

    /**
     * 推荐接口最多返回条数
     */
    private static final int MAX_RECOMMENDATIONS = 8;

    private static final String ORIGINAL_PDF = "ORIGINAL_PDF";

    /**
     * 拉丁词：英文、数字及常见技术符号
     */
    private static final Pattern LATIN_TOKEN = Pattern.compile("[a-z0-9][a-z0-9+#\\-_.]*");

    /**
     * 中文连续片段
     */
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    private final DocumentMapper documentMapper;
    private final DocumentFileMapper documentFileMapper;
    private final DocumentTagMapper documentTagMapper;
    private final ParseRecordMapper parseRecordMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final AiTaskMapper aiTaskMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final FavoriteMapper favoriteMapper;
    private final NoteMapper noteMapper;
    private final AiAnalysisMapper aiAnalysisMapper;
    private final DocumentAccessService documentAccessService;
    private final FileStorageService fileStorageService;
    private final ITaskService taskService;

    public DocumentServiceImpl(DocumentMapper documentMapper,
                               DocumentFileMapper documentFileMapper,
                               DocumentTagMapper documentTagMapper,
                               ParseRecordMapper parseRecordMapper,
                               DocumentChunkMapper documentChunkMapper,
                               AiTaskMapper aiTaskMapper,
                               CategoryMapper categoryMapper,
                               TagMapper tagMapper,
                               FavoriteMapper favoriteMapper,
                               NoteMapper noteMapper,
                               AiAnalysisMapper aiAnalysisMapper,
                               DocumentAccessService documentAccessService,
                               FileStorageService fileStorageService,
                               ITaskService taskService) {
        this.documentMapper = documentMapper;
        this.documentFileMapper = documentFileMapper;
        this.documentTagMapper = documentTagMapper;
        this.parseRecordMapper = parseRecordMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.aiTaskMapper = aiTaskMapper;
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
        this.favoriteMapper = favoriteMapper;
        this.noteMapper = noteMapper;
        this.aiAnalysisMapper = aiAnalysisMapper;
        this.documentAccessService = documentAccessService;
        this.fileStorageService = fileStorageService;
        this.taskService = taskService;
    }

    /* ------------------------------------------------------------------ */
    /* 列表                                                                 */
    /* ------------------------------------------------------------------ */

    @Override
    @Transactional(readOnly = true)
    public PageResult<DocumentVO> listDocuments(DocumentQueryDTO query) {
        Long userId = CurrentUser.getCurrentUserId();

        QueryWrapper<Document> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);

        String keyword = trimToNull(query.getKeyword());
        if (keyword != null) {
            // keywords / authors 是 JSON 列，MySQL 会隐式转为字符串后匹配
            wrapper.and(w -> w.like("title", keyword)
                    .or().like("abstract", keyword)
                    .or().like("keywords", keyword)
                    .or().like("authors", keyword));
        }

        String author = trimToNull(query.getAuthor());
        if (author != null) {
            wrapper.like("authors", author);
        }

        if (query.getYear() != null) {
            wrapper.eq("publication_year", query.getYear());
        }

        if (query.getTagId() != null) {
            List<Long> documentIds = documentTagMapper.selectList(
                            new LambdaQueryWrapper<DocumentTag>().eq(DocumentTag::getTagId, query.getTagId()))
                    .stream()
                    .map(DocumentTag::getDocumentId)
                    .distinct()
                    .collect(Collectors.toList());

            if (documentIds.isEmpty()) {
                return emptyPage(query);
            }
            wrapper.in("id", documentIds);
        }

        if (query.getCategoryId() != null) {
            Set<Long> categoryIds = collectCategoryIds(userId, query.getCategoryId());
            if (categoryIds.isEmpty()) {
                return emptyPage(query);
            }
            wrapper.in("category_id", categoryIds);
        }

        if (Boolean.TRUE.equals(query.getFavorite())) {
            List<Long> documentIds = favoriteMapper.selectList(
                            new LambdaQueryWrapper<Favorite>()
                                    .eq(Favorite::getUserId, userId)
                                    .select(Favorite::getDocumentId))
                    .stream()
                    .map(Favorite::getDocumentId)
                    .distinct()
                    .collect(Collectors.toList());

            if (documentIds.isEmpty()) {
                return emptyPage(query);
            }
            wrapper.in("id", documentIds);
        }

        String parseStatus = trimToNull(query.getParseStatus());
        if (parseStatus != null) {
            wrapper.eq("parse_status", parseStatus);
        }

        applySort(wrapper, query.getSort());

        Page<Document> page = new Page<>(query.pageNo(), query.pageSize());
        IPage<Document> result = documentMapper.selectPage(page, wrapper);

        List<DocumentVO> records = toVOList(result.getRecords(), userId);

        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    /* ------------------------------------------------------------------ */
    /* 上传                                                                 */
    /* ------------------------------------------------------------------ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UploadResultVO upload(MultipartFile file,
                                 String title,
                                 String authors,
                                 String keywords,
                                 String journal,
                                 String doi,
                                 Integer publicationYear,
                                 String documentType,
                                 Long categoryId,
                                 List<String> tagIds,
                                 String abstractText,
                                 String language) {
        Long userId = CurrentUser.getCurrentUserId();

        // 1. 文件校验：非空、PDF 后缀、大小上限
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "上传文件不能为空");
        }

        String originalName = trimToNull(file.getOriginalFilename());
        if (originalName == null) {
            originalName = "document.pdf";
        }

        if (!originalName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new BusinessException(400, "仅支持 PDF 格式的文献文件");
        }

        if (file.getSize() > MAX_PDF_SIZE) {
            throw new BusinessException(400, "文件大小超过 50MB 限制");
        }

        // 2. 标题为空时用文件名（去掉 .pdf）
        String effectiveTitle = trimToNull(title);
        if (effectiveTitle == null) {
            effectiveTitle = originalName.replaceAll("(?i)\\.pdf$", "").trim();
        }
        if (effectiveTitle.isEmpty()) {
            effectiveTitle = "未命名文献";
        }

        // 3. 重复检测：DOI 相同，或标题去掉所有空白并转小写后相同
        String doiValue = trimToNull(doi);
        detectDuplicate(userId, effectiveTitle, doiValue);

        // 4. 分类 / 标签必须属于当前用户
        validateCategory(userId, categoryId);
        List<Long> tagIdList = parseTagIds(tagIds);
        validateTags(userId, tagIdList);

        // 5. 落库文献
        Document document = new Document();
        document.setUserId(userId);
        document.setTitle(effectiveTitle);
        document.setAuthors(splitValues(authors));
        document.setAbstractText(trimToNull(abstractText));
        document.setKeywords(splitValues(keywords));
        document.setPublicationYear(publicationYear != null && publicationYear > 0 ? publicationYear : null);
        document.setJournal(trimToNull(journal));
        document.setDoi(doiValue);
        document.setLanguage(trimToNull(language) == null ? "英文" : trimToNull(language));
        document.setDocumentType(trimToNull(documentType) == null ? "JOURNAL" : trimToNull(documentType));
        document.setCategoryId(categoryId);
        document.setSource("手动上传");
        document.setParseStatus(ParseStatus.PENDING.name());
        document.setDeleted(0);
        documentMapper.insert(document);

        // 6. 保存原始 PDF
        FileStorageService.StoredFile stored =
                fileStorageService.storeOriginalPdf(document.getId(), originalName, file);

        DocumentFile documentFile = new DocumentFile();
        documentFile.setDocumentId(document.getId());
        documentFile.setFileType(ORIGINAL_PDF);
        documentFile.setFileName(stored.fileName());
        documentFile.setStoragePath(stored.relativePath());
        documentFile.setFileSize(stored.size());
        documentFile.setFileHash(stored.sha256());
        documentFile.setVersion(1);
        documentFileMapper.insert(documentFile);

        // 7. 创建解析任务（AiTaskRunner 异步执行）
        Map<String, Object> requestParams = new LinkedHashMap<>();
        requestParams.put("fileName", stored.fileName());
        requestParams.put("filePath", stored.relativePath());
        AiTask task = taskService.createTask(userId, document.getId(), TaskType.PDF_PARSE, requestParams);

        // 8. 创建解析记录；解析成功后由任务处理器切换 is_current
        ParseRecord parseRecord = new ParseRecord();
        parseRecord.setDocumentId(document.getId());
        parseRecord.setTaskId(task.getId());
        parseRecord.setParserName("MinerU");
        parseRecord.setStatus(ParseStatus.PENDING.name());
        parseRecord.setIsCurrent(0);
        parseRecordMapper.insert(parseRecord);

        // 9. 保存标签关联
        for (Long tagId : tagIdList) {
            DocumentTag documentTag = new DocumentTag();
            documentTag.setDocumentId(document.getId());
            documentTag.setTagId(tagId);
            documentTagMapper.insert(documentTag);
        }

        // 10. 返回新建文献（重新查询以取得数据库默认时间字段）
        Document saved = documentMapper.selectById(document.getId());
        DocumentVO documentVO = toVOList(List.of(saved), userId).get(0);

        UploadResultVO uploadResult = new UploadResultVO();
        uploadResult.setDocument(documentVO);
        uploadResult.setTaskId(task.getId());
        uploadResult.setParseRecordId(parseRecord.getId());

        return uploadResult;
    }

    /* ------------------------------------------------------------------ */
    /* 详情                                                                 */
    /* ------------------------------------------------------------------ */

    @Override
    @Transactional(readOnly = true)
    public DocumentDetailVO getDocumentDetail(Long documentId) {
        Long userId = CurrentUser.getCurrentUserId();
        Document document = documentAccessService.requireOwned(userId, documentId);

        DocumentVO base = toVOList(List.of(document), userId).get(0);

        DocumentDetailVO detail = new DocumentDetailVO();
        BeanUtils.copyProperties(base, detail);

        // 文件
        List<DocumentFile> files = documentFileMapper.selectList(
                new LambdaQueryWrapper<DocumentFile>()
                        .eq(DocumentFile::getDocumentId, documentId)
                        .orderByAsc(DocumentFile::getId));
        List<DocumentFileVO> fileVOs = files.stream()
                .map(DocumentConvert::toFileVO)
                .collect(Collectors.toList());

        // 解析记录：按 id 倒序
        List<ParseRecord> parseRecords = parseRecordMapper.selectList(
                new LambdaQueryWrapper<ParseRecord>()
                        .eq(ParseRecord::getDocumentId, documentId)
                        .orderByDesc(ParseRecord::getId));
        List<ParseRecordVO> parseRecordVOs = parseRecords.stream()
                .map(DocumentConvert::toParseRecordVO)
                .collect(Collectors.toList());

        // 历史分析：全部版本，按创建时间倒序
        List<AiAnalysis> analyses = aiAnalysisMapper.selectList(
                new LambdaQueryWrapper<AiAnalysis>()
                        .eq(AiAnalysis::getDocumentId, documentId)
                        .orderByDesc(AiAnalysis::getCreatedAt)
                        .orderByDesc(AiAnalysis::getId));
        List<AnalysisVO> analysisVOs = analyses.stream()
                .map(DocumentConvert::toAnalysisVO)
                .collect(Collectors.toList());

        // 笔记：按更新时间倒序
        List<Note> notes = noteMapper.selectList(
                new LambdaQueryWrapper<Note>()
                        .eq(Note::getDocumentId, documentId)
                        .eq(Note::getUserId, userId)
                        .orderByDesc(Note::getUpdatedAt)
                        .orderByDesc(Note::getId));
        List<NoteVO> noteVOs = notes.stream()
                .map(note -> DocumentConvert.toNoteVO(note, document.getTitle()))
                .collect(Collectors.toList());

        // 相关文献：复用推荐逻辑
        List<RecommendationItemVO> related = recommendations(documentId, 4);

        // 最新任务
        List<AiTask> latestTasks = aiTaskMapper.selectList(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getDocumentId, documentId)
                        .orderByDesc(AiTask::getCreatedAt)
                        .orderByDesc(AiTask::getId)
                        .last("LIMIT 1"));
        TaskVO latestTask = latestTasks.isEmpty()
                ? null
                : DocumentConvert.toTaskVO(latestTasks.get(0), document.getTitle());

        detail.setFiles(fileVOs);
        detail.setParseRecords(parseRecordVOs);
        detail.setAnalyses(analysisVOs);
        detail.setNotes(noteVOs);
        detail.setRelated(related);
        detail.setLatestTask(latestTask);

        return detail;
    }

    /* ------------------------------------------------------------------ */
    /* 更新 / 删除 / 收藏                                                   */
    /* ------------------------------------------------------------------ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentVO updateDocument(Long documentId, DocumentUpdateDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();
        documentAccessService.requireOwned(userId, documentId);

        // 非空字段通过实体更新，才能走 authors / keywords 的 JacksonTypeHandler
        Document patch = new Document();
        patch.setId(documentId);
        boolean patchChanged = false;

        if (dto.getTitle() != null) {
            String title = dto.getTitle().trim();
            if (title.isEmpty()) {
                throw new BusinessException(400, "论文标题不能为空");
            }
            patch.setTitle(title);
            patchChanged = true;
        }

        if (dto.getAbstractText() != null) {
            patch.setAbstractText(dto.getAbstractText().trim());
            patchChanged = true;
        }

        if (dto.getAuthors() != null) {
            patch.setAuthors(cleanValues(dto.getAuthors()));
            patchChanged = true;
        }

        if (dto.getKeywords() != null) {
            patch.setKeywords(cleanValues(dto.getKeywords()));
            patchChanged = true;
        }

        if (dto.getDocumentType() != null) {
            String documentType = trimToNull(dto.getDocumentType());
            patch.setDocumentType(documentType == null ? "JOURNAL" : documentType);
            patchChanged = true;
        }

        if (patchChanged) {
            documentMapper.updateById(patch);
        }

        // 可清空字段用 UpdateWrapper 显式 set，允许把值改为 NULL
        UpdateWrapper<Document> update = new UpdateWrapper<>();
        update.eq("id", documentId);
        boolean changed = false;

        if (dto.isJournalPresent()) {
            update.set("journal", trimToNull(dto.getJournal()));
            changed = true;
        }

        if (dto.isDoiPresent()) {
            update.set("doi", trimToNull(dto.getDoi()));
            changed = true;
        }

        if (dto.isPublicationYearPresent()) {
            update.set("publication_year", dto.getPublicationYear());
            changed = true;
        }

        if (dto.isCategoryIdPresent()) {
            validateCategory(userId, dto.getCategoryId());
            update.set("category_id", dto.getCategoryId());
            changed = true;
        }

        if (changed) {
            documentMapper.update(null, update);
        }

        // 标签：物理删除旧关联后重建
        if (dto.getTagIds() != null) {
            List<Long> tagIds = distinctIds(dto.getTagIds());
            validateTags(userId, tagIds);

            documentTagMapper.delete(new LambdaQueryWrapper<DocumentTag>()
                    .eq(DocumentTag::getDocumentId, documentId));

            for (Long tagId : tagIds) {
                DocumentTag documentTag = new DocumentTag();
                documentTag.setDocumentId(documentId);
                documentTag.setTagId(tagId);
                documentTagMapper.insert(documentTag);
            }
        }

        Document updated = documentMapper.selectById(documentId);
        if (updated == null) {
            throw new BusinessException(404, "文献不存在或已被删除");
        }

        return toVOList(List.of(updated), userId).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long documentId) {
        Long userId = CurrentUser.getCurrentUserId();
        documentAccessService.requireOwned(userId, documentId);

        // 文献逻辑删除；解析记录 / 文本块 / 分析结果保留以便追溯
        documentMapper.deleteById(documentId);

        // 标签、收藏关联物理删除
        documentTagMapper.delete(new LambdaQueryWrapper<DocumentTag>()
                .eq(DocumentTag::getDocumentId, documentId));
        favoriteMapper.delete(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getDocumentId, documentId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> toggleFavorite(Long documentId) {
        Long userId = CurrentUser.getCurrentUserId();
        documentAccessService.requireOwned(userId, documentId);

        Favorite existing = favoriteMapper.selectOne(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getDocumentId, documentId)
                .last("LIMIT 1"));

        Map<String, Object> result = new LinkedHashMap<>();

        if (existing != null) {
            favoriteMapper.deleteById(existing.getId());
            result.put("favorite", false);
        } else {
            Favorite favorite = new Favorite();
            favorite.setUserId(userId);
            favorite.setDocumentId(documentId);
            favoriteMapper.insert(favorite);
            result.put("favorite", true);
        }

        return result;
    }

    /* ------------------------------------------------------------------ */
    /* 正文 / 分析 / 重新解析                                               */
    /* ------------------------------------------------------------------ */

    @Override
    @Transactional(readOnly = true)
    public DocumentContentVO getDocumentContent(Long documentId) {
        Long userId = CurrentUser.getCurrentUserId();
        Document document = documentAccessService.requireOwned(userId, documentId);

        Long parseRecordId = document.getCurrentParseRecordId();
        if (parseRecordId == null) {
            throw new BusinessException(409, "该文献尚未完成解析，暂无正文内容");
        }

        ParseRecord parseRecord = parseRecordMapper.selectById(parseRecordId);
        if (parseRecord == null) {
            throw new BusinessException(409, "该文献尚未完成解析，暂无正文内容");
        }

        List<DocumentChunk> chunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getDocumentId, documentId)
                        .eq(DocumentChunk::getParseRecordId, parseRecordId)
                        .orderByAsc(DocumentChunk::getChunkIndex));

        // 连续相同 section 的文本块合并为一节
        List<DocumentSectionVO> sections = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            DocumentSectionVO last = sections.isEmpty() ? null : sections.get(sections.size() - 1);
            if (last != null && Objects.equals(last.getSection(), chunk.getSection())) {
                last.setMarkdown(last.getMarkdown() + "\n\n" + nullToEmpty(chunk.getContent()));
                last.setPageEnd(chunk.getPageEnd());
                continue;
            }

            DocumentSectionVO section = new DocumentSectionVO();
            section.setSection(chunk.getSection());
            section.setPageStart(chunk.getPageStart());
            section.setPageEnd(chunk.getPageEnd());
            section.setMarkdown(nullToEmpty(chunk.getContent()));
            sections.add(section);
        }

        DocumentContentVO content = new DocumentContentVO();
        content.setDocumentId(documentId);
        content.setParseRecordId(parseRecordId);
        content.setPageCount(parseRecord.getPageCount() == null ? 0 : parseRecord.getPageCount());
        content.setSections(sections);
        content.setChunks(DocumentConvert.toChunkVOList(chunks));

        return content;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> analyzeDocument(Long documentId, AnalyzeRequestDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();
        Document document = documentAccessService.requireOwned(userId, documentId);

        // 同一文献只允许一个进行中的分析任务，避免并发写入多个分析版本
        Long runningAnalyses = aiTaskMapper.selectCount(new LambdaQueryWrapper<AiTask>()
                .eq(AiTask::getDocumentId, documentId)
                .eq(AiTask::getTaskType, TaskType.AI_ANALYSIS.name())
                .in(AiTask::getStatus, TaskStatus.PENDING.name(), TaskStatus.PROCESSING.name()));

        if (runningAnalyses != null && runningAnalyses > 0) {
            throw new BusinessException(409, "该文献已有进行中的 AI 分析任务，请等待其完成");
        }

        Long currentParseRecordId = document.getCurrentParseRecordId();
        if (currentParseRecordId == null) {
            throw new BusinessException(409, "该文献暂无可用的解析版本，无法进行 AI 分析");
        }

        // 以“当前有效解析版本”为准，而不是最近一次解析尝试状态：
        // 重新解析失败时 parse_status 会是 FAILED，但旧版本仍然可用。
        ParseRecord currentParseRecord = parseRecordMapper.selectById(currentParseRecordId);
        if (currentParseRecord == null
                || !ParseStatus.SUCCESS.name().equals(currentParseRecord.getStatus())) {
            throw new BusinessException(409, "该文献暂无可用的解析版本，无法进行 AI 分析");
        }

        String requestedType = dto == null ? null : trimToNull(dto.getAnalysisType());
        String analysisType = requestedType == null ? AnalysisType.FULL_ANALYSIS.name() : requestedType;

        try {
            AnalysisType.valueOf(analysisType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "不支持的分析类型：" + analysisType);
        }

        Map<String, Object> requestParams = new LinkedHashMap<>();
        requestParams.put("analysisType", analysisType);

        AiTask task = taskService.createTask(userId, documentId, TaskType.AI_ANALYSIS, requestParams);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getId());
        result.put("analysisType", analysisType);

        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReparseResultVO reparseDocument(Long documentId) {
        Long userId = CurrentUser.getCurrentUserId();
        documentAccessService.requireOwned(userId, documentId);

        // 1. 同一文献已有进行中的解析任务时不允许重复发起
        Long running = aiTaskMapper.selectCount(new LambdaQueryWrapper<AiTask>()
                .eq(AiTask::getDocumentId, documentId)
                .eq(AiTask::getTaskType, TaskType.PDF_PARSE.name())
                .in(AiTask::getStatus, TaskStatus.PENDING.name(), TaskStatus.PROCESSING.name()));

        if (running != null && running > 0) {
            throw new BusinessException(409, "该文献已有进行中的解析任务，请等待其完成");
        }

        // 2. 取得最新原始 PDF
        List<DocumentFile> originalFiles = documentFileMapper.selectList(
                new LambdaQueryWrapper<DocumentFile>()
                        .eq(DocumentFile::getDocumentId, documentId)
                        .eq(DocumentFile::getFileType, ORIGINAL_PDF)
                        .orderByDesc(DocumentFile::getId)
                        .last("LIMIT 1"));

        if (originalFiles.isEmpty() || !fileStorageService.exists(originalFiles.get(0).getStoragePath())) {
            throw new BusinessException(400, "原始 PDF 文件不存在，无法重新解析");
        }
        DocumentFile originalFile = originalFiles.get(0);

        // 3. 新建任务
        Map<String, Object> requestParams = new LinkedHashMap<>();
        requestParams.put("fileName", originalFile.getFileName());
        requestParams.put("filePath", originalFile.getStoragePath());
        AiTask task = taskService.createTask(userId, documentId, TaskType.PDF_PARSE, requestParams);

        // 4. 新建解析记录（旧版本保持 current 不变）
        ParseRecord parseRecord = new ParseRecord();
        parseRecord.setDocumentId(documentId);
        parseRecord.setTaskId(task.getId());
        parseRecord.setParserName("MinerU");
        parseRecord.setStatus(ParseStatus.PENDING.name());
        parseRecord.setIsCurrent(0);
        parseRecordMapper.insert(parseRecord);

        // 5. 最近一次解析尝试状态置为解析中
        Document update = new Document();
        update.setId(documentId);
        update.setParseStatus(ParseStatus.PROCESSING.name());
        documentMapper.updateById(update);

        ReparseResultVO result = new ReparseResultVO();
        result.setTaskId(task.getId());
        result.setParseRecordId(parseRecord.getId());

        return result;
    }

    /**
     * 读取原始 PDF（受控下载；归属校验在方法内部完成）
     */
    @Override
    @Transactional(readOnly = true)
    public IDocumentService.OriginalPdf loadOriginalPdf(Long documentId) {
        Long userId = CurrentUser.getCurrentUserId();
        documentAccessService.requireOwned(userId, documentId);

        List<DocumentFile> files = documentFileMapper.selectList(new LambdaQueryWrapper<DocumentFile>()
                .eq(DocumentFile::getDocumentId, documentId)
                .eq(DocumentFile::getFileType, ORIGINAL_PDF)
                .orderByDesc(DocumentFile::getId)
                .last("LIMIT 1"));

        if (files.isEmpty()) {
            throw new BusinessException(404, "该文献没有原始 PDF 文件");
        }

        DocumentFile file = files.get(0);
        if (file.getStoragePath() == null || !fileStorageService.exists(file.getStoragePath())) {
            throw new BusinessException(404, "原始 PDF 文件不存在或已被清理");
        }

        Path path = fileStorageService.resolve(file.getStoragePath());
        String fileName = file.getFileName() == null || file.getFileName().isBlank()
                ? "document.pdf"
                : file.getFileName();

        return new IDocumentService.OriginalPdf(fileName, new FileSystemResource(path));
    }

    /* ------------------------------------------------------------------ */
    /* 相似文献推荐                                                         */
    /* ------------------------------------------------------------------ */

    @Override
    @Transactional(readOnly = true)
    public List<RecommendationItemVO> recommendations(Long documentId, int limit) {
        Document target = documentAccessService.requireOwned(documentId);

        int max = Math.min(Math.max(limit, 1), MAX_RECOMMENDATIONS);

        List<Document> candidates = documentMapper.selectList(new QueryWrapper<Document>()
                .select("id", "title", "authors", "abstract", "keywords",
                        "publication_year", "journal", "category_id")
                .eq("user_id", target.getUserId())
                .ne("id", documentId));

        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }

        // 一次性取出目标文献与候选文献的标签关联
        List<Long> allDocumentIds = new ArrayList<>();
        allDocumentIds.add(documentId);
        candidates.forEach(candidate -> allDocumentIds.add(candidate.getId()));

        List<DocumentTag> links = documentTagMapper.selectList(
                new LambdaQueryWrapper<DocumentTag>().in(DocumentTag::getDocumentId, allDocumentIds));

        Map<Long, Set<Long>> tagIdsByDocument = new HashMap<>();
        for (DocumentTag link : links) {
            tagIdsByDocument.computeIfAbsent(link.getDocumentId(), key -> new HashSet<>())
                    .add(link.getTagId());
        }

        Set<Long> tagIds = links.stream()
                .map(DocumentTag::getTagId)
                .collect(Collectors.toSet());

        Map<Long, String> tagNames = new HashMap<>();
        if (!tagIds.isEmpty()) {
            List<Tag> tags = tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                    .in(Tag::getId, tagIds)
                    .eq(Tag::getUserId, target.getUserId()));
            tags.forEach(tag -> tagNames.put(tag.getId(), tag.getName()));
        }

        String targetCategoryName = null;
        if (target.getCategoryId() != null) {
            Category category = categoryMapper.selectById(target.getCategoryId());
            targetCategoryName = category == null ? null : category.getName();
        }

        Set<Long> targetTagIds = tagIdsByDocument.getOrDefault(documentId, Set.of());
        Set<String> targetTerms = termsOf(target);

        List<RecommendationItemVO> recommendations = new ArrayList<>();
        for (Document candidate : candidates) {
            Set<Long> candidateTagIds = tagIdsByDocument.getOrDefault(candidate.getId(), Set.of());

            List<String> sharedTagNames = candidateTagIds.stream()
                    .filter(targetTagIds::contains)
                    .map(tagNames::get)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());

            double termSimilarity = jaccard(targetTerms, termsOf(candidate));
            boolean sameCategory = Objects.equals(candidate.getCategoryId(), target.getCategoryId());

            double score = termSimilarity * 1.5
                    + sharedTagNames.size() * 0.12
                    + (sameCategory ? 0.18 : 0);
            score = Math.min(0.99, score);
            score = Math.round(score * 1000) / 1000.0;

            List<String> reasons = new ArrayList<>();
            if (!sharedTagNames.isEmpty()) {
                reasons.add("共享标签：" + String.join("、", sharedTagNames));
            }
            if (sameCategory) {
                reasons.add("同属分类：" + (targetCategoryName == null ? "未分类" : targetCategoryName));
            }
            if (termSimilarity > 0.05) {
                reasons.add("摘要与关键词语义相近");
            }

            String reason = reasons.isEmpty() ? "由向量相似度检索得到" : String.join("；", reasons);

            recommendations.add(DocumentConvert.toRecommendationItem(candidate, score, reason));
        }

        recommendations.sort(Comparator
                .comparingDouble(RecommendationItemVO::getScore).reversed()
                .thenComparing(RecommendationItemVO::getDocumentId));

        if (recommendations.size() > max) {
            return new ArrayList<>(recommendations.subList(0, max));
        }

        return recommendations;
    }

    /* ------------------------------------------------------------------ */
    /* 内部工具                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * 批量补全标签、分类名、收藏状态、文件大小、页数，避免 N+1 查询
     */
    private List<DocumentVO> toVOList(List<Document> documents, Long userId) {
        List<DocumentVO> results = new ArrayList<>();
        if (documents == null || documents.isEmpty()) {
            return results;
        }

        List<Long> documentIds = documents.stream()
                .map(Document::getId)
                .collect(Collectors.toList());

        // 1. 标签关联 + 标签信息（两次查询）
        Map<Long, List<TagVO>> tagsByDocument = new HashMap<>();
        List<DocumentTag> links = documentTagMapper.selectList(new LambdaQueryWrapper<DocumentTag>()
                .in(DocumentTag::getDocumentId, documentIds)
                .orderByAsc(DocumentTag::getId));

        if (!links.isEmpty()) {
            Set<Long> tagIds = links.stream()
                    .map(DocumentTag::getTagId)
                    .collect(Collectors.toSet());

            List<Tag> tags = tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                    .in(Tag::getId, tagIds)
                    .eq(Tag::getUserId, userId));

            Map<Long, TagVO> tagVOs = new HashMap<>();
            tags.forEach(tag -> tagVOs.put(tag.getId(), DocumentConvert.toTagVO(tag)));

            for (DocumentTag link : links) {
                TagVO tagVO = tagVOs.get(link.getTagId());
                if (tagVO != null) {
                    tagsByDocument.computeIfAbsent(link.getDocumentId(), key -> new ArrayList<>())
                            .add(tagVO);
                }
            }
        }

        // 2. 分类名称
        Set<Long> categoryIds = documents.stream()
                .map(Document::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> categoryNames = new HashMap<>();
        if (!categoryIds.isEmpty()) {
            categoryMapper.selectBatchIds(categoryIds)
                    .forEach(category -> categoryNames.put(category.getId(), category.getName()));
        }

        // 3. 收藏状态
        Set<Long> favoriteDocumentIds = favoriteMapper.selectList(new LambdaQueryWrapper<Favorite>()
                        .eq(Favorite::getUserId, userId)
                        .in(Favorite::getDocumentId, documentIds))
                .stream()
                .map(Favorite::getDocumentId)
                .collect(Collectors.toSet());

        // 4. 原始 PDF 大小（同一文献取最新一条）
        Map<Long, Long> fileSizes = new HashMap<>();
        documentFileMapper.selectList(new LambdaQueryWrapper<DocumentFile>()
                        .in(DocumentFile::getDocumentId, documentIds)
                        .eq(DocumentFile::getFileType, ORIGINAL_PDF)
                        .orderByDesc(DocumentFile::getId))
                .forEach(file -> fileSizes.putIfAbsent(
                        file.getDocumentId(),
                        file.getFileSize() == null ? 0L : file.getFileSize()));

        // 5. 页数（当前解析记录）
        Set<Long> parseRecordIds = documents.stream()
                .map(Document::getCurrentParseRecordId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, Integer> pageCounts = new HashMap<>();
        if (!parseRecordIds.isEmpty()) {
            parseRecordMapper.selectBatchIds(parseRecordIds)
                    .forEach(record -> pageCounts.put(record.getId(), record.getPageCount()));
        }

        for (Document document : documents) {
            DocumentVO vo = DocumentConvert.toVO(document);
            vo.setTags(tagsByDocument.getOrDefault(document.getId(), new ArrayList<>()));
            vo.setCategoryName(categoryNames.get(document.getCategoryId()));
            vo.setFavorite(favoriteDocumentIds.contains(document.getId()));
            vo.setFileSize(fileSizes.getOrDefault(document.getId(), 0L));

            Integer pageCount = document.getCurrentParseRecordId() == null
                    ? null
                    : pageCounts.get(document.getCurrentParseRecordId());
            vo.setPageCount(pageCount == null ? 0 : pageCount);

            results.add(vo);
        }

        return results;
    }

    /**
     * 收集某分类及其全部子孙分类ID
     */
    private Set<Long> collectCategoryIds(Long userId, Long rootCategoryId) {
        Set<Long> result = new LinkedHashSet<>();
        if (rootCategoryId == null) {
            return result;
        }
        result.add(rootCategoryId);

        List<Category> categories = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .eq(Category::getUserId, userId));

        boolean changed = true;
        while (changed) {
            changed = false;
            for (Category category : categories) {
                if (category.getParentId() != null
                        && result.contains(category.getParentId())
                        && result.add(category.getId())) {
                    changed = true;
                }
            }
        }

        return result;
    }

    /**
     * 排序：白名单外的值一律按创建时间倒序
     */
    private void applySort(QueryWrapper<Document> wrapper, String sort) {
        String sortKey = sort == null ? "createdAt" : sort;

        switch (sortKey) {
            case "updatedAt" -> wrapper.orderByDesc("updated_at").orderByDesc("id");
            case "publicationYear" -> wrapper.orderByDesc("publication_year")
                    .orderByDesc("created_at")
                    .orderByDesc("id");
            case "title" -> wrapper.orderByAsc("title").orderByAsc("id");
            default -> wrapper.orderByDesc("created_at").orderByDesc("id");
        }
    }

    /**
     * 重复检测：DOI 相同，或标题去掉所有空白并转小写后相同
     */
    private void detectDuplicate(Long userId, String title, String doi) {
        List<Document> existingDocuments = documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .select(Document::getId, Document::getTitle, Document::getDoi)
                        .eq(Document::getUserId, userId));

        String normalizedTitle = normalizeTitle(title);

        for (Document existing : existingDocuments) {
            boolean sameDoi = doi != null
                    && existing.getDoi() != null
                    && doi.equalsIgnoreCase(existing.getDoi().trim());

            boolean sameTitle = existing.getTitle() != null
                    && normalizedTitle.equals(normalizeTitle(existing.getTitle()));

            if (sameDoi || sameTitle) {
                throw new BusinessException(409,
                        "检测到疑似重复文献：《" + existing.getTitle()
                                + "》。系统不会自动删除已有记录，请确认后再处理。");
            }
        }
    }

    /**
     * 去掉所有空白字符并转小写
     */
    private String normalizeTitle(String title) {
        StringBuilder builder = new StringBuilder(title.length());
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
                builder.append(c);
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * 校验分类属于当前用户
     */
    private void validateCategory(Long userId, Long categoryId) {
        if (categoryId == null) {
            return;
        }

        Category category = categoryMapper.selectById(categoryId);
        if (category == null || !userId.equals(category.getUserId())) {
            throw new BusinessException(400, "分类不存在或不属于当前用户");
        }
    }

    /**
     * 校验标签属于当前用户
     */
    private void validateTags(Long userId, List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }

        List<Tag> tags = tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                .in(Tag::getId, tagIds)
                .eq(Tag::getUserId, userId));

        if (tags.size() != tagIds.size()) {
            throw new BusinessException(400, "标签不存在或不属于当前用户");
        }
    }

    /**
     * 解析上传表单中的标签参数（支持逗号分隔单值与重复参数）
     */
    private List<Long> parseTagIds(List<String> rawTagIds) {
        List<Long> result = new ArrayList<>();
        if (rawTagIds == null) {
            return result;
        }

        for (String group : rawTagIds) {
            if (group == null) {
                continue;
            }
            for (String part : group.split("[,，、;；\\s]+")) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    Long tagId = Long.valueOf(part.trim());
                    if (!result.contains(tagId)) {
                        result.add(tagId);
                    }
                } catch (NumberFormatException e) {
                    throw new BusinessException(400, "标签参数格式不正确");
                }
            }
        }

        return result;
    }

    private List<Long> distinctIds(List<Long> ids) {
        List<Long> result = new ArrayList<>();
        if (ids == null) {
            return result;
        }
        for (Long id : ids) {
            if (id != null && !result.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    /**
     * 按逗号 / 顿号 / 分号 / 换行拆分字符串
     */
    private List<String> splitValues(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null) {
            return values;
        }

        for (String part : raw.split("[,，、;；\\n\\r]+")) {
            String value = part.trim();
            if (!value.isEmpty() && !values.contains(value)) {
                values.add(value);
            }
        }

        return values;
    }

    /**
     * 清洗 DTO 中的字符串列表
     */
    private List<String> cleanValues(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }

        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }

        return result;
    }

    /**
     * 中英文混合分词：英文按单词，中文按 2-gram
     */
    private Set<String> tokenize(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }

        String lower = text.toLowerCase(Locale.ROOT);

        Matcher latinMatcher = LATIN_TOKEN.matcher(lower);
        while (latinMatcher.find()) {
            terms.add(latinMatcher.group());
        }

        Matcher cjkMatcher = CJK_RUN.matcher(lower);
        while (cjkMatcher.find()) {
            String run = cjkMatcher.group();
            if (run.length() <= 2) {
                terms.add(run);
                continue;
            }
            for (int i = 0; i + 2 <= run.length(); i++) {
                terms.add(run.substring(i, i + 2));
            }
        }

        return terms;
    }

    /**
     * 文献的词项集合：关键词 + 标题 + 摘要
     */
    private Set<String> termsOf(Document document) {
        Set<String> terms = new LinkedHashSet<>();

        if (document.getKeywords() != null) {
            document.getKeywords().forEach(keyword -> terms.addAll(tokenize(keyword)));
        }
        terms.addAll(tokenize(document.getTitle()));
        terms.addAll(tokenize(document.getAbstractText()));

        return terms;
    }

    /**
     * Jaccard 相似度
     */
    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }

        int intersection = 0;
        for (String term : left) {
            if (right.contains(term)) {
                intersection++;
            }
        }

        int union = left.size() + right.size() - intersection;
        return union == 0 ? 0 : (double) intersection / union;
    }

    private PageResult<DocumentVO> emptyPage(DocumentQueryDTO query) {
        return new PageResult<>(0L, query.pageNo(), query.pageSize(), new ArrayList<>());
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
