package com.research.platform.modules.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.research.platform.common.enums.ParseStatus;
import com.research.platform.common.enums.TaskType;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.infrastructure.ai.AiCommands;
import com.research.platform.infrastructure.ai.AiResults;
import com.research.platform.infrastructure.ai.AiServiceClient;
import com.research.platform.infrastructure.ai.AiTaskExecutionResult;
import com.research.platform.infrastructure.ai.AiTaskHandler;
import com.research.platform.infrastructure.storage.FileStorageService;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.entity.DocumentChunk;
import com.research.platform.modules.document.entity.DocumentFile;
import com.research.platform.modules.document.entity.ParseRecord;
import com.research.platform.modules.document.mapper.DocumentChunkMapper;
import com.research.platform.modules.document.mapper.DocumentFileMapper;
import com.research.platform.modules.document.mapper.DocumentMapper;
import com.research.platform.modules.document.mapper.ParseRecordMapper;
import com.research.platform.modules.knowledge.service.KnowledgeExtractionService;
import com.research.platform.modules.task.entity.AiTask;
import com.research.platform.modules.task.service.ITaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PDF 解析任务处理器
 *
 * <p>执行流程：定位解析记录 → 读取原始 PDF → 调用 AI 服务解析（事务外）→
 * 保存解析产物 → 事务内写入文本块并发布解析版本。</p>
 *
 * <p>失败时只把 parse_record 置为 FAILED、document.parse_status 置为 FAILED，
 * 不切换 current_parse_record_id；ai_task 状态由 {@code AiTaskRunner} 维护。</p>
 */
@Component
public class DocumentParseTaskHandler implements AiTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseTaskHandler.class);

    private static final String ORIGINAL_PDF = "ORIGINAL_PDF";

    private static final String PARSED_MARKDOWN = "PARSED_MARKDOWN";

    private static final String PARSED_JSON = "PARSED_JSON";

    private static final String PARSER_NAME = "MinerU";

    private static final int MAX_ERROR_LENGTH = 2000;

    private final DocumentMapper documentMapper;
    private final DocumentFileMapper documentFileMapper;
    private final ParseRecordMapper parseRecordMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final AiServiceClient aiServiceClient;
    private final FileStorageService fileStorageService;
    private final ITaskService taskService;
    private final KnowledgeExtractionService knowledgeExtractionService;
    private final TransactionTemplate transactionTemplate;

    public DocumentParseTaskHandler(DocumentMapper documentMapper,
                                    DocumentFileMapper documentFileMapper,
                                    ParseRecordMapper parseRecordMapper,
                                    DocumentChunkMapper documentChunkMapper,
                                    AiServiceClient aiServiceClient,
                                    FileStorageService fileStorageService,
                                    ITaskService taskService,
                                    KnowledgeExtractionService knowledgeExtractionService,
                                    PlatformTransactionManager transactionManager) {
        this.documentMapper = documentMapper;
        this.documentFileMapper = documentFileMapper;
        this.parseRecordMapper = parseRecordMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.aiServiceClient = aiServiceClient;
        this.fileStorageService = fileStorageService;
        this.taskService = taskService;
        this.knowledgeExtractionService = knowledgeExtractionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public TaskType supports() {
        return TaskType.PDF_PARSE;
    }

    @Override
    public AiTaskExecutionResult handle(AiTask task) {
        Long documentId = task.getDocumentId();
        if (documentId == null) {
            return AiTaskExecutionResult.fail("解析任务缺少关联文献");
        }

        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            return AiTaskExecutionResult.fail("文献不存在或已被删除");
        }

        taskService.updateProgress(task.getId(), 10, "读取原始 PDF");

        // 1. 定位或补建解析记录（正常情况下上传/重新解析时已创建）
        ParseRecord parseRecord = findOrCreateParseRecord(task, documentId);
        Long parseRecordId = parseRecord.getId();

        // 2. 读取原始 PDF
        List<DocumentFile> originalFiles = documentFileMapper.selectList(
                new LambdaQueryWrapper<DocumentFile>()
                        .eq(DocumentFile::getDocumentId, documentId)
                        .eq(DocumentFile::getFileType, ORIGINAL_PDF)
                        .orderByDesc(DocumentFile::getId)
                        .last("LIMIT 1"));

        String missingMessage = "原始 PDF 文件不存在，无法解析";
        if (originalFiles.isEmpty()) {
            markFailed(documentId, parseRecordId, missingMessage);
            return AiTaskExecutionResult.fail(missingMessage);
        }

        DocumentFile originalFile = originalFiles.get(0);

        try {
            // 3. 校验原始文件并调用 AI 解析（必须在数据库事务之外执行）
            if (originalFile.getStoragePath() == null
                    || !fileStorageService.exists(originalFile.getStoragePath())) {
                throw new BusinessException(400, missingMessage);
            }

            AiResults.Parse result = aiServiceClient.parse(new AiCommands.Parse(
                    documentId,
                    parseRecordId,
                    task.getId(),
                    originalFile.getStoragePath(),
                    originalFile.getFileName()));

            if (result == null) {
                throw new BusinessException(503, "AI 服务返回空的解析结果");
            }

            taskService.updateProgress(task.getId(), 55, "解析完成，保存结果");

            // 4. 落盘解析产物（结果可能为空，为空时不写文件、文件ID置空）
            FileStorageService.StoredFile markdownFile = result.markdown() == null
                    ? null
                    : fileStorageService.storeParsedFile(
                            documentId, parseRecordId, "content.md",
                            result.markdown().getBytes(StandardCharsets.UTF_8));

            FileStorageService.StoredFile jsonFile = result.json() == null
                    ? null
                    : fileStorageService.storeParsedFile(
                            documentId, parseRecordId, "content.json",
                            result.json().getBytes(StandardCharsets.UTF_8));

            // 5. 事务内落库解析文件与文本块；此时还不发布版本
            transactionTemplate.executeWithoutResult(status ->
                    persistParsedResult(documentId, parseRecordId, result, markdownFile, jsonFile));

            // 6. 先完成向量化并通过完整性检查，成功后才能发布新版本
            taskService.updateProgress(task.getId(), 80, "生成文本向量并校验");
            IndexOutcome indexOutcome = indexChunks(documentId, parseRecordId);

            if (!indexOutcome.success()) {
                String message = "向量化失败，未发布新解析版本：" + indexOutcome.message();
                markFailed(documentId, parseRecordId, message);
                return AiTaskExecutionResult.fail(message);
            }

            // 7. 向量完整后，事务内切换当前解析版本
            taskService.updateProgress(task.getId(), 90, "发布解析版本");
            transactionTemplate.executeWithoutResult(status ->
                    publishVersion(documentId, parseRecordId));

            // 8. 事务提交后再清理旧版本向量；失败只记日志，旧向量由检索过滤兜底
            taskService.updateProgress(task.getId(), 93, "清理旧版本向量");
            cleanupOldVectors(documentId, parseRecordId);

            // 9. 自动识别作者/期刊/DOI/摘要/关键词等元数据（只补空字段，失败不影响解析）
            taskService.updateProgress(task.getId(), 95, "识别文献元数据");
            enrichMetadata(documentId, parseRecordId, result);

            // 10. 抽取知识实体/关系（PAPER/METHOD/MODEL/DATASET/INSTITUTION 等）
            taskService.updateProgress(task.getId(), 97, "抽取知识实体与关系");
            Document current = documentMapper.selectById(documentId);
            knowledgeExtractionService.extractAndSave(
                    current == null ? null : current.getUserId(),
                    documentId,
                    parseRecordId,
                    markdownFile == null ? null : markdownFile.relativePath(),
                    current == null ? null : current.getTitle());

            taskService.updateProgress(task.getId(), 99, "解析完成");

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("parseRecordId", parseRecordId);
            summary.put("pageCount", result.pageCount());
            summary.put("chunkCount", result.chunks() == null ? 0 : result.chunks().size());

            return AiTaskExecutionResult.ok("PDF 解析完成", summary);
        } catch (Exception e) {
            String message = resolveMessage(e);
            log.warn("PDF 解析任务失败：taskId={}, documentId={}, parseRecordId={}, message={}",
                    task.getId(), documentId, parseRecordId, message);

            markFailed(documentId, parseRecordId, message);

            // 不向 runner 抛异常，避免重复记录失败原因
            return AiTaskExecutionResult.fail(message);
        }
    }

    /**
     * 解析完成后自动补全文文献元数据。
     *
     * <p>只填空白字段；标题仅在“系统自动生成”时替换（空、未命名、或等于文件名），
     * 用户手工填写的标题不会被覆盖。</p>
     */
    private void enrichMetadata(Long documentId, Long parseRecordId, AiResults.Parse result) {
        Document document = documentMapper.selectById(documentId);

        if (document == null || result == null) {
            return;
        }

        DocumentFile original = documentFileMapper.selectOne(
                new LambdaQueryWrapper<DocumentFile>()
                        .eq(DocumentFile::getDocumentId, documentId)
                        .eq(DocumentFile::getFileType, ORIGINAL_PDF)
                        .orderByDesc(DocumentFile::getId)
                        .last("LIMIT 1"));

        if (original == null) {
            return;
        }

        // 元数据已经填全时不再调用模型，避免无意义的额外耗时/费用
        boolean needsEnrichment = isBlank(document.getTitle())
                || "未命名文献".equals(document.getTitle())
                || matchesFileName(document.getTitle(), original.getFileName())
                || isEmpty(document.getAuthors())
                || isBlank(document.getAbstractText())
                || isEmpty(document.getKeywords())
                || isBlank(document.getJournal())
                || isBlank(document.getDoi())
                || document.getPublicationYear() == null
                || isBlank(document.getDocumentType());

        if (!needsEnrichment) {
            return;
        }

        try {
            AiResults.Metadata metadata = aiServiceClient.extractMetadata(new AiCommands.Metadata(
                    documentId,
                    parseRecordId,
                    original.getStoragePath(),
                    original.getFileName(),
                    result.markdown(),
                    document.getTitle()));

            if (metadata == null) {
                return;
            }

            boolean changed = false;
            boolean autoTitle = isBlank(document.getTitle())
                    || "未命名文献".equals(document.getTitle())
                    || matchesFileName(document.getTitle(), original.getFileName());

            if (autoTitle && !isBlank(metadata.title())) {
                document.setTitle(metadata.title().trim());
                changed = true;
            }

            if (isEmpty(document.getAuthors()) && metadata.authors() != null
                    && !metadata.authors().isEmpty()) {
                document.setAuthors(metadata.authors());
                changed = true;
            }

            if (isBlank(document.getAbstractText()) && !isBlank(metadata.abstractText())) {
                document.setAbstractText(metadata.abstractText().trim());
                changed = true;
            }

            if (isEmpty(document.getKeywords()) && metadata.keywords() != null
                    && !metadata.keywords().isEmpty()) {
                document.setKeywords(metadata.keywords());
                changed = true;
            }

            if (isBlank(document.getJournal()) && !isBlank(metadata.journal())) {
                document.setJournal(metadata.journal().trim());
                changed = true;
            }

            if (isBlank(document.getDoi()) && !isBlank(metadata.doi())) {
                document.setDoi(metadata.doi().trim());
                changed = true;
            }

            if (document.getPublicationYear() == null && metadata.publicationYear() != null) {
                document.setPublicationYear(metadata.publicationYear());
                changed = true;
            }

            if (isBlank(document.getDocumentType()) && !isBlank(metadata.documentType())) {
                document.setDocumentType(metadata.documentType());
                changed = true;
            } else if (autoTitle
                    && "JOURNAL".equals(document.getDocumentType())
                    && !isBlank(metadata.documentType())
                    && !"JOURNAL".equals(metadata.documentType())) {
                // 前端默认选“期刊论文”，自动标题场景下允许纠正为会议/预印本等
                document.setDocumentType(metadata.documentType());
                changed = true;
            }

            if (isBlank(document.getLanguage()) && !isBlank(metadata.language())) {
                document.setLanguage(metadata.language());
                changed = true;
            }

            if (changed) {
                documentMapper.updateById(document);
                log.info("已自动补全文文献元数据：documentId={}, title={}, model={}",
                        documentId, document.getTitle(), metadata.modelName());
            }
        } catch (Exception e) {
            log.warn("元数据自动识别失败，保留上传时填写的信息：documentId={}, reason={}",
                    documentId, e.getMessage());
        }
    }

    private boolean matchesFileName(String title, String fileName) {
        if (isBlank(title) || isBlank(fileName)) {
            return false;
        }
        String stem = fileName.replaceAll("(?i)\\.pdf$", "").trim();
        return title.trim().equalsIgnoreCase(stem);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }

    /**
     * 把当前解析版本的文本块交给 AI 服务做向量化。
     *
     * <p>向量化失败不改变解析任务结果：解析文本已经可用，只是语义检索暂时查不到
     * 这批文本块；后续可重新解析或补偿任务重建索引。</p>
     */
    private IndexOutcome indexChunks(Long documentId, Long parseRecordId) {
        List<DocumentChunk> chunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getParseRecordId, parseRecordId));

        if (chunks.isEmpty()) {
            return new IndexOutcome(true, "没有文本块，跳过向量化");
        }

        List<AiCommands.IndexChunk> payload = chunks.stream()
                .map(chunk -> new AiCommands.IndexChunk(
                        chunk.getChunkId(),
                        chunk.getUserId(),
                        chunk.getDocumentId(),
                        chunk.getParseRecordId(),
                        chunk.getSection(),
                        chunk.getPageStart(),
                        chunk.getPageEnd(),
                        chunk.getContent()))
                .toList();

        try {
            AiResults.Index indexed = aiServiceClient.indexChunks(new AiCommands.Index(payload));

            int expected = payload.size();
            int ok = indexed == null || indexed.indexed() == null ? 0 : indexed.indexed();
            int failed = indexed == null || indexed.failed() == null ? 0 : indexed.failed();

            log.info("文本块向量化完成：documentId={}, parseRecordId={}, expected={}, indexed={}, failed={}",
                    documentId, parseRecordId, expected, ok, failed);

            if (indexed == null || failed > 0 || ok < expected) {
                String detail = indexed == null || indexed.message() == null
                        ? "向量条数不完整（indexed=" + ok + "/" + expected + ", failed=" + failed + "）"
                        : indexed.message();
                return new IndexOutcome(false, detail);
            }

            return new IndexOutcome(true, "indexed=" + ok);
        } catch (Exception e) {
            log.warn("文本块向量化失败：documentId={}, parseRecordId={}, reason={}",
                    documentId, parseRecordId, e.getMessage());
            return new IndexOutcome(false, e.getMessage());
        }
    }

    /**
     * 向量化结果：只有全部文本块成功才算通过，才允许发布新版本。
     */
    private record IndexOutcome(boolean success, String message) {
    }

    /**
     * 通过 task_id 定位解析记录；缺失时补建一条（不设为当前版本）
     */
    private ParseRecord findOrCreateParseRecord(AiTask task, Long documentId) {
        List<ParseRecord> records = parseRecordMapper.selectList(
                new LambdaQueryWrapper<ParseRecord>()
                        .eq(ParseRecord::getTaskId, task.getId())
                        .orderByDesc(ParseRecord::getId)
                        .last("LIMIT 1"));

        if (!records.isEmpty()) {
            return records.get(0);
        }

        ParseRecord parseRecord = new ParseRecord();
        parseRecord.setDocumentId(documentId);
        parseRecord.setTaskId(task.getId());
        parseRecord.setParserName(PARSER_NAME);
        parseRecord.setStatus(ParseStatus.PROCESSING.name());
        parseRecord.setIsCurrent(0);
        parseRecordMapper.insert(parseRecord);

        return parseRecord;
    }

    /**
     * 事务内：写解析文件记录、更新解析记录为 PROCESSING、重建文本块。
     *
     * <p>此步骤不切换当前版本；只有向量化成功后才调用 {@link #publishVersion}。</p>
     */
    private void persistParsedResult(Long documentId,
                                     Long parseRecordId,
                                     AiResults.Parse result,
                                     FileStorageService.StoredFile markdownFile,
                                     FileStorageService.StoredFile jsonFile) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException(404, "文献不存在或已被删除");
        }

        Long markdownFileId = insertParsedFile(documentId, PARSED_MARKDOWN, markdownFile);
        Long jsonFileId = insertParsedFile(documentId, PARSED_JSON, jsonFile);

        int pageCount = result.pageCount() == null ? 0 : result.pageCount();
        String parserName = result.parserName() == null || result.parserName().isBlank()
                ? PARSER_NAME
                : result.parserName();

        // 1. 解析记录只进入 PROCESSING，记录解析器与产物
        parseRecordMapper.update(null, new LambdaUpdateWrapper<ParseRecord>()
                .eq(ParseRecord::getId, parseRecordId)
                .set(ParseRecord::getStatus, ParseStatus.PROCESSING.name())
                .set(ParseRecord::getParserName, parserName)
                .set(ParseRecord::getParserVersion, result.parserVersion())
                .set(ParseRecord::getMarkdownFileId, markdownFileId)
                .set(ParseRecord::getJsonFileId, jsonFileId)
                .set(ParseRecord::getPageCount, pageCount)
                .set(ParseRecord::getCompletedAt, null)
                .set(ParseRecord::getErrorMessage, null));

        // 2. 重建该版本的文本块（重试时先清理旧数据）
        documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getParseRecordId, parseRecordId));

        List<AiResults.Chunk> chunks = result.chunks();
        if (chunks != null) {
            int index = 1;
            for (AiResults.Chunk chunk : chunks) {
                DocumentChunk entity = new DocumentChunk();
                entity.setChunkId(String.format(Locale.ROOT, "CHUNK_%d_%d_%03d",
                        documentId, parseRecordId, index));
                entity.setUserId(document.getUserId());
                entity.setDocumentId(documentId);
                entity.setParseRecordId(parseRecordId);
                entity.setChunkIndex(index);
                entity.setSection(chunk.section());
                entity.setPageStart(chunk.pageStart());
                entity.setPageEnd(chunk.pageEnd());
                entity.setContent(chunk.content());
                entity.setTokenCount(chunk.tokenCount());
                entity.setEmbeddingStatus("PENDING");
                entity.setEmbeddingModel(null);
                documentChunkMapper.insert(entity);

                index++;
            }
        }

        // 3. 最近一次解析尝试进入处理中；当前有效版本保持不变
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, documentId)
                .set(Document::getParseStatus, ParseStatus.PROCESSING.name()));
    }

    /**
     * 事务内发布新解析版本：只有此时才切换 is_current 与 current_parse_record_id。
     */
    private void publishVersion(Long documentId, Long parseRecordId) {
        parseRecordMapper.update(null, new LambdaUpdateWrapper<ParseRecord>()
                .eq(ParseRecord::getId, parseRecordId)
                .set(ParseRecord::getStatus, ParseStatus.SUCCESS.name())
                .set(ParseRecord::getCompletedAt, LocalDateTime.now())
                .set(ParseRecord::getErrorMessage, null));

        // 先释放同文献旧版本的 is_current，再设置本条
        parseRecordMapper.update(null, new LambdaUpdateWrapper<ParseRecord>()
                .eq(ParseRecord::getDocumentId, documentId)
                .ne(ParseRecord::getId, parseRecordId)
                .set(ParseRecord::getIsCurrent, 0));

        parseRecordMapper.update(null, new LambdaUpdateWrapper<ParseRecord>()
                .eq(ParseRecord::getId, parseRecordId)
                .set(ParseRecord::getIsCurrent, 1));

        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, documentId)
                .set(Document::getCurrentParseRecordId, parseRecordId)
                .set(Document::getParseStatus, ParseStatus.SUCCESS.name()));
    }

    /**
     * 清理旧解析版本的向量；新版本必须已经发布成功。
     */
    private void cleanupOldVectors(Long documentId, Long keepParseRecordId) {
        try {
            AiResults.Cleanup cleanup = aiServiceClient.cleanupVectors(
                    new AiCommands.Cleanup(documentId, keepParseRecordId));

            log.info("旧版本向量清理完成：documentId={}, keepParseRecordId={}, deleted={}",
                    documentId, keepParseRecordId, cleanup == null ? null : cleanup.deleted());
        } catch (Exception e) {
            log.warn("旧版本向量清理失败（不影响当前版本）：documentId={}, keepParseRecordId={}, reason={}",
                    documentId, keepParseRecordId, e.getMessage());
        }
    }

    /**
     * 保存解析产物对应的 document_file 记录，内容为空时返回 null
     */
    private Long insertParsedFile(Long documentId,
                                  String fileType,
                                  FileStorageService.StoredFile storedFile) {
        if (storedFile == null) {
            return null;
        }

        DocumentFile documentFile = new DocumentFile();
        documentFile.setDocumentId(documentId);
        documentFile.setFileType(fileType);
        documentFile.setFileName(storedFile.fileName());
        documentFile.setStoragePath(storedFile.relativePath());
        documentFile.setFileSize(storedFile.size());
        documentFile.setFileHash(storedFile.sha256());
        documentFile.setVersion(1);
        documentFileMapper.insert(documentFile);

        return documentFile.getId();
    }

    /**
     * 失败处理：只更新解析记录与文献最近一次解析状态，不切换当前版本
     */
    private void markFailed(Long documentId, Long parseRecordId, String message) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (parseRecordId != null) {
                    parseRecordMapper.update(null, new LambdaUpdateWrapper<ParseRecord>()
                            .eq(ParseRecord::getId, parseRecordId)
                            .set(ParseRecord::getStatus, ParseStatus.FAILED.name())
                            .set(ParseRecord::getErrorMessage, truncate(message)));
                }

                documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                        .eq(Document::getId, documentId)
                        .set(Document::getParseStatus, ParseStatus.FAILED.name()));
            });
        } catch (Exception e) {
            // 失败状态落库异常不应影响 runner 记录任务结果
            log.error("更新解析失败状态时出错：documentId={}, parseRecordId={}", documentId, parseRecordId, e);
        }
    }

    private String resolveMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
