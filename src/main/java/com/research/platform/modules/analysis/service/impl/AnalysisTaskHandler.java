package com.research.platform.modules.analysis.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.research.platform.common.enums.AnalysisStatus;
import com.research.platform.common.enums.AnalysisType;
import com.research.platform.common.enums.TaskType;
import com.research.platform.infrastructure.ai.AiCommands;
import com.research.platform.infrastructure.ai.AiResults;
import com.research.platform.infrastructure.ai.AiServiceClient;
import com.research.platform.infrastructure.ai.AiTaskExecutionResult;
import com.research.platform.infrastructure.ai.AiTaskHandler;
import com.research.platform.infrastructure.storage.FileStorageService;
import com.research.platform.modules.analysis.entity.AiAnalysis;
import com.research.platform.modules.analysis.mapper.AiAnalysisMapper;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.entity.DocumentFile;
import com.research.platform.modules.document.entity.ParseRecord;
import com.research.platform.modules.document.mapper.DocumentFileMapper;
import com.research.platform.modules.document.mapper.DocumentMapper;
import com.research.platform.modules.document.mapper.ParseRecordMapper;
import com.research.platform.modules.model.entity.ModelCallLog;
import com.research.platform.modules.model.mapper.ModelCallLogMapper;
import com.research.platform.modules.task.entity.AiTask;
import com.research.platform.modules.task.service.ITaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文献 AI 分析任务处理器
 *
 * <p>执行流程：</p>
 * <ol>
 *   <li>从任务参数中解析分析类型，读取文献与当前有效解析版本的 Markdown；</li>
 *   <li>在事务外调用 FastAPI，避免长耗时调用占用数据库事务；</li>
 *   <li>成功后“先清旧、再插新”，保证 uk_analysis_current 唯一索引不冲突；</li>
 *   <li>失败时写入 FAILED 版本用于追溯，旧的有效版本保持不变。</li>
 * </ol>
 */
@Component
public class AnalysisTaskHandler implements AiTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskHandler.class);

    private static final int MAX_ERROR_LENGTH = 2000;

    private static final String NOT_PARSED_MESSAGE = "该文献尚未完成解析，无法进行 AI 分析";

    private static final String LLM_MODEL_TYPE = "LLM";

    private final DocumentMapper documentMapper;
    private final ParseRecordMapper parseRecordMapper;
    private final DocumentFileMapper documentFileMapper;
    private final AiAnalysisMapper aiAnalysisMapper;
    private final ModelCallLogMapper modelCallLogMapper;
    private final AiServiceClient aiServiceClient;
    private final FileStorageService fileStorageService;
    private final ITaskService taskService;
    private final TransactionTemplate transactionTemplate;

    public AnalysisTaskHandler(
            DocumentMapper documentMapper,
            ParseRecordMapper parseRecordMapper,
            DocumentFileMapper documentFileMapper,
            AiAnalysisMapper aiAnalysisMapper,
            ModelCallLogMapper modelCallLogMapper,
            AiServiceClient aiServiceClient,
            FileStorageService fileStorageService,
            ITaskService taskService,
            PlatformTransactionManager transactionManager
    ) {
        this.documentMapper = documentMapper;
        this.parseRecordMapper = parseRecordMapper;
        this.documentFileMapper = documentFileMapper;
        this.aiAnalysisMapper = aiAnalysisMapper;
        this.modelCallLogMapper = modelCallLogMapper;
        this.aiServiceClient = aiServiceClient;
        this.fileStorageService = fileStorageService;
        this.taskService = taskService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public TaskType supports() {
        return TaskType.AI_ANALYSIS;
    }

    @Override
    public AiTaskExecutionResult handle(AiTask task) {
        // 1. 校验任务与文献
        if (task.getDocumentId() == null) {
            return AiTaskExecutionResult.fail("任务未关联文献，无法进行 AI 分析");
        }

        Document document = documentMapper.selectById(task.getDocumentId());
        if (document == null || !Objects.equals(document.getUserId(), task.getUserId())) {
            return AiTaskExecutionResult.fail("文献不存在或无权访问");
        }

        String analysisType = resolveAnalysisType(task);
        if (!isValidAnalysisType(analysisType)) {
            return AiTaskExecutionResult.fail("不支持的分析类型：" + analysisType);
        }

        // 2. 读取当前有效解析版本的 Markdown
        String markdownPath = resolveMarkdownPath(document);
        if (markdownPath == null) {
            return failWithRecord(task, document, analysisType, NOT_PARSED_MESSAGE, 0L, false);
        }

        String markdown = readMarkdown(markdownPath);
        if (!StringUtils.hasText(markdown)) {
            return failWithRecord(task, document, analysisType, NOT_PARSED_MESSAGE, 0L, false);
        }

        // 3. AI 调用在事务外执行
        long startedAt = System.currentTimeMillis();
        AiResults.Analysis result;

        taskService.updateProgress(task.getId(), 30, "读取原文完成，调用模型生成分析");

        try {
            result = aiServiceClient.analyze(new AiCommands.Analyze(
                    document.getId(),
                    task.getId(),
                    analysisType,
                    document.getTitle(),
                    document.getAbstractText(),
                    markdownPath
            ));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startedAt;
            String reason = "AI 分析失败：" + resolveMessage(e);
            log.warn("AI 分析失败：taskId={}, documentId={}, reason={}",
                    task.getId(), document.getId(), resolveMessage(e));
            return failWithRecord(task, document, analysisType, reason, duration, true);
        }

        long duration = System.currentTimeMillis() - startedAt;

        taskService.updateProgress(task.getId(), 80, "保存分析结果");

        // 4. 成功结果落库（事务内）
        try {
            AiAnalysis saved = transactionTemplate.execute(status ->
                    saveSuccess(task, document, analysisType, result, duration));

            if (saved == null) {
                return AiTaskExecutionResult.fail("AI 分析结果保存失败");
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("analysisId", saved.getId());
            summary.put("analysisType", analysisType);
            summary.put("version", saved.getVersion());
            summary.put("modelName", saved.getModelName());

            return AiTaskExecutionResult.ok(
                    "AI 分析完成（" + analysisType + " v" + saved.getVersion() + "）",
                    summary
            );
        } catch (Exception e) {
            log.error("保存 AI 分析结果失败：taskId={}, documentId={}", task.getId(), document.getId(), e);
            return failWithRecord(task, document, analysisType,
                    "保存 AI 分析结果失败：" + resolveMessage(e), duration, true);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 落库                                                               */
    /* ------------------------------------------------------------------ */

    /**
     * 成功结果落库：先清理同文献同类型的旧当前版本，再插入新版本
     */
    private AiAnalysis saveSuccess(
            AiTask task,
            Document document,
            String analysisType,
            AiResults.Analysis result,
            long duration
    ) {
        int version = nextVersion(document.getId(), analysisType);

        clearCurrentVersion(document.getId(), analysisType);

        LocalDateTime now = LocalDateTime.now();

        AiAnalysis analysis = new AiAnalysis();
        analysis.setDocumentId(document.getId());
        analysis.setTaskId(task.getId());
        analysis.setAnalysisType(analysisType);
        analysis.setStatus(AnalysisStatus.SUCCESS.name());
        analysis.setVersion(version);
        analysis.setIsCurrent(1);
        analysis.setSummary(result.summary());
        analysis.setResearchBackground(result.researchBackground());
        analysis.setResearchMethod(result.researchMethod());
        analysis.setInnovationPoints(result.innovationPoints() == null ? List.of() : result.innovationPoints());
        analysis.setExperimentalResults(result.experimentalResults());
        analysis.setConclusion(result.conclusion());
        analysis.setKeywords(result.keywords() == null ? List.of() : result.keywords());
        analysis.setModelName(result.modelName());
        analysis.setCreatedAt(now);
        analysis.setCompletedAt(now);

        aiAnalysisMapper.insert(analysis);

        // 记录模型调用，仪表盘的 AI 用量统计依赖该表
        insertCallLog(task.getId(), result.modelName(), result.promptTokens(), result.completionTokens(),
                duration, AnalysisStatus.SUCCESS.name(), null);

        return analysis;
    }

    /**
     * 失败结果落库：is_current = 0，不覆盖旧的有效版本
     *
     * @param logCall 是否同步写入模型调用日志（AI 调用发生后才需要）
     */
    private AiTaskExecutionResult failWithRecord(
            AiTask task,
            Document document,
            String analysisType,
            String reason,
            long duration,
            boolean logCall
    ) {
        String error = truncate(reason);

        try {
            transactionTemplate.executeWithoutResult(status -> {
                int version = nextVersion(document.getId(), analysisType);
                LocalDateTime now = LocalDateTime.now();

                AiAnalysis analysis = new AiAnalysis();
                analysis.setDocumentId(document.getId());
                analysis.setTaskId(task.getId());
                analysis.setAnalysisType(analysisType);
                analysis.setStatus(AnalysisStatus.FAILED.name());
                analysis.setVersion(version);
                analysis.setIsCurrent(0);
                analysis.setErrorMessage(error);
                analysis.setCreatedAt(now);
                analysis.setCompletedAt(now);
                aiAnalysisMapper.insert(analysis);

                if (logCall) {
                    insertCallLog(task.getId(), null, null, null, duration,
                            AnalysisStatus.FAILED.name(), error);
                }
            });
        } catch (Exception e) {
            // 失败记录本身写不进去时，不能掩盖原始错误
            log.error("写入 AI 分析失败记录时出错：taskId={}, documentId={}",
                    task.getId(), document.getId(), e);
        }

        return AiTaskExecutionResult.fail(error);
    }

    /**
     * 计算新版本号：同文献同类型的最大版本 + 1
     */
    private int nextVersion(Long documentId, String analysisType) {
        AiAnalysis latest = aiAnalysisMapper.selectOne(new QueryWrapper<AiAnalysis>()
                .eq("document_id", documentId)
                .eq("analysis_type", analysisType)
                .orderByDesc("version")
                .last("LIMIT 1"));

        if (latest == null || latest.getVersion() == null) {
            return 1;
        }
        return latest.getVersion() + 1;
    }

    /**
     * 清理旧的有效版本（生成列 current_document_id/current_analysis_type 随之置空）
     */
    private void clearCurrentVersion(Long documentId, String analysisType) {
        aiAnalysisMapper.update(null, new LambdaUpdateWrapper<AiAnalysis>()
                .eq(AiAnalysis::getDocumentId, documentId)
                .eq(AiAnalysis::getAnalysisType, analysisType)
                .eq(AiAnalysis::getIsCurrent, 1)
                .set(AiAnalysis::getIsCurrent, 0));
    }

    /**
     * 写模型调用日志；仪表盘按用户汇总近 7 天调用量与 Token
     */
    private void insertCallLog(
            Long taskId,
            String modelName,
            Integer promptTokens,
            Integer completionTokens,
            long duration,
            String status,
            String errorMessage
    ) {
        ModelCallLog callLog = new ModelCallLog();
        callLog.setTaskId(taskId);
        callLog.setModelName(modelName);
        callLog.setModelType(LLM_MODEL_TYPE);
        callLog.setRequestTokens(promptTokens);
        callLog.setResponseTokens(completionTokens);
        callLog.setDurationMs(duration);
        callLog.setStatus(status);
        callLog.setErrorMessage(truncate(errorMessage));
        callLog.setCreatedAt(LocalDateTime.now());
        modelCallLogMapper.insert(callLog);
    }

    /* ------------------------------------------------------------------ */
    /* 辅助方法                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 解析分析类型，缺省为 FULL_ANALYSIS
     */
    private String resolveAnalysisType(AiTask task) {
        Map<String, Object> params = task.getRequestParams();
        if (params == null) {
            return AnalysisType.FULL_ANALYSIS.name();
        }

        Object value = params.get("analysisType");
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return AnalysisType.FULL_ANALYSIS.name();
        }
        return String.valueOf(value).trim();
    }

    private boolean isValidAnalysisType(String analysisType) {
        try {
            AnalysisType.valueOf(analysisType);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * document.current_parse_record_id → parse_record.markdown_file_id → document_file.storage_path
     */
    private String resolveMarkdownPath(Document document) {
        if (document.getCurrentParseRecordId() == null) {
            return null;
        }

        ParseRecord parseRecord = parseRecordMapper.selectById(document.getCurrentParseRecordId());
        if (parseRecord == null || parseRecord.getMarkdownFileId() == null) {
            return null;
        }

        DocumentFile markdownFile = documentFileMapper.selectById(parseRecord.getMarkdownFileId());
        if (markdownFile == null) {
            return null;
        }

        return markdownFile.getStoragePath();
    }

    /**
     * 读取 Markdown 原文，路径非法或文件缺失时返回 null
     */
    private String readMarkdown(String markdownPath) {
        try {
            return fileStorageService.readText(markdownPath);
        } catch (Exception e) {
            log.warn("读取解析文件失败：path={}, reason={}", markdownPath, resolveMessage(e));
            return null;
        }
    }

    private String resolveMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
