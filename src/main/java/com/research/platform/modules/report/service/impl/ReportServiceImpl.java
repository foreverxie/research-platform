package com.research.platform.modules.report.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.research.platform.common.dto.PageQueryDTO;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.common.result.PageResult;
import com.research.platform.modules.analysis.entity.AiAnalysis;
import com.research.platform.modules.analysis.mapper.AiAnalysisMapper;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.service.DocumentAccessService;
import com.research.platform.modules.report.convert.ReportConvert;
import com.research.platform.modules.report.dto.ReportCreateDTO;
import com.research.platform.modules.report.entity.Report;
import com.research.platform.modules.report.mapper.ReportMapper;
import com.research.platform.modules.report.service.IReportService;
import com.research.platform.modules.report.util.MarkdownDocxConverter;
import com.research.platform.modules.report.vo.ExportFile;
import com.research.platform.modules.report.vo.ReportVO;
import com.research.platform.utils.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 分析报告服务实现
 *
 * <p>报告正文不调用 AI，而是基于当前有效的 AI 分析结果（优先 FULL_ANALYSIS）
 * 与文献元数据拼装 Markdown，缺失分析时明确提示「尚未生成 AI 分析结果」。</p>
 */
@Service
public class ReportServiceImpl extends ServiceImpl<ReportMapper, Report> implements IReportService {

    private static final String DEFAULT_REPORT_TYPE = "LITERATURE_ANALYSIS";

    private static final String REPORT_NOTICE =
            "本报告由平台基于已保存的 AI 分析结果与文献元数据自动生成，AI 归纳内容需人工核对原文。";

    private static final String MISSING_ANALYSIS = "尚未生成 AI 分析结果。";

    private static final String FORMAT_MARKDOWN = "markdown";

    private static final String FORMAT_DOCX = "docx";

    private static final String MARKDOWN_CONTENT_TYPE = "text/markdown;charset=UTF-8";

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final String DEFAULT_FILE_NAME = "未命名报告";

    /**
     * 文件名最大长度，避免 Content-Disposition 头过长
     */
    private static final int MAX_FILE_NAME_LENGTH = 80;

    private static final Set<String> REPORT_TYPES = Set.of(
            "LITERATURE_ANALYSIS", "TOPIC_REVIEW", "COMPARE", "CUSTOM"
    );

    private final ReportMapper reportMapper;
    private final AiAnalysisMapper aiAnalysisMapper;
    private final DocumentAccessService documentAccessService;

    public ReportServiceImpl(
            ReportMapper reportMapper,
            AiAnalysisMapper aiAnalysisMapper,
            DocumentAccessService documentAccessService
    ) {
        this.reportMapper = reportMapper;
        this.aiAnalysisMapper = aiAnalysisMapper;
        this.documentAccessService = documentAccessService;
    }

    /**
     * 分页查询当前用户的报告
     */
    @Override
    public PageResult<ReportVO> page(PageQueryDTO query) {
        Long userId = CurrentUser.getCurrentUserId();

        Page<Report> page = reportMapper.selectPage(
                new Page<>(query.pageNo(), query.pageSize()),
                new QueryWrapper<Report>()
                        // 列表不返回 Markdown 正文，避免响应过大
                        .select("id", "user_id", "title", "report_type", "document_ids",
                                "status", "summary", "created_at", "updated_at")
                        .eq("user_id", userId)
                        .orderByDesc("created_at")
                        .orderByDesc("id")
        );

        // 批量收集报告关联文献，一次补齐标题
        Set<Long> documentIds = new LinkedHashSet<>();
        for (Report report : page.getRecords()) {
            if (report.getDocumentIds() != null) {
                for (Long documentId : report.getDocumentIds()) {
                    if (documentId != null) {
                        documentIds.add(documentId);
                    }
                }
            }
        }
        Map<Long, String> titleMap = resolveTitleMap(userId, documentIds);

        List<ReportVO> records = new ArrayList<>(page.getRecords().size());
        for (Report report : page.getRecords()) {
            records.add(ReportConvert.toVO(report, titlesOf(report.getDocumentIds(), titleMap)));
        }
        Page<ReportVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(records);
        return PageResult.of(voPage);
    }

    /**
     * 查询报告详情
     */
    @Override
    public ReportVO detail(Long id) {
        Long userId = CurrentUser.getCurrentUserId();
        Report report = getOwnedReport(userId, id);
        Map<Long, String> titleMap = resolveTitleMap(userId, report.getDocumentIds());
        return ReportConvert.toVO(report, titlesOf(report.getDocumentIds(), titleMap));
    }

    /**
     * 基于已保存的 AI 分析结果与文献元数据生成报告（不调用 AI）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportVO create(ReportCreateDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();

        // 1. 标题与类型规范化
        String title = dto.getTitle() == null ? "" : dto.getTitle().trim();
        if (title.isEmpty()) {
            throw new BusinessException(400, "报告标题不能为空");
        }
        String reportType = resolveReportType(dto.getReportType());

        // 2. 文献范围校验
        List<Long> requestedIds = normalizeDocumentIds(dto.getDocumentIds());
        if (requestedIds.isEmpty()) {
            throw new BusinessException(400, "请至少选择一篇文献");
        }
        List<Document> documents = loadOwnedDocuments(userId, requestedIds);

        // 3. 取每篇文献当前有效的分析结果
        Map<Long, AiAnalysis> analyses = loadCurrentAnalyses(requestedIds);

        // 4. 组装 Markdown 正文
        String content = buildContent(title, documents, analyses);

        // 5. 落库
        Report report = new Report();
        report.setUserId(userId);
        report.setTitle(title);
        report.setReportType(reportType);
        report.setDocumentIds(requestedIds);
        report.setStatus("COMPLETED");
        report.setSummary(
                "基于 " + documents.size()
                        + " 篇文献的 AI 分析结果生成的结构化报告，包含研究背景、方法、创新点与结论。"
        );
        report.setContent(content);
        reportMapper.insert(report);

        // 6. 返回 VO（documentTitles 顺序与 documentIds 一致）
        Report saved = reportMapper.selectById(report.getId());
        List<String> titles = new ArrayList<>(documents.size());
        for (Document document : documents) {
            titles.add(documentTitle(document));
        }
        return ReportConvert.toVO(saved, titles);
    }

    /**
     * 导出报告：Markdown 直接返回正文，DOCX 由 Markdown 转换生成
     *
     * <p>Markdown 走 UTF-8 编码；DOCX 使用 Apache POI 的 XWPF 生成，标题/引用/列表/表格行
     * 按 Markdown 语法做简化排版。报告归属校验复用 {@link #getOwnedReport}，不存在按 404 处理。</p>
     */
    @Override
    public ExportFile export(Long id, String format) {
        Long userId = CurrentUser.getCurrentUserId();
        Report report = getOwnedReport(userId, id);

        String normalizedFormat = normalizeExportFormat(format);
        String fileName = sanitizeFileName(report.getTitle());
        String content = report.getContent() == null ? "" : report.getContent();

        if (FORMAT_DOCX.equals(normalizedFormat)) {
            return new ExportFile(
                    fileName + ".docx",
                    DOCX_CONTENT_TYPE,
                    MarkdownDocxConverter.toDocx(content)
            );
        }
        return new ExportFile(
                fileName + ".md",
                MARKDOWN_CONTENT_TYPE,
                content.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * 删除报告（逻辑删除）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Long userId = CurrentUser.getCurrentUserId();
        Report report = getOwnedReport(userId, id);
        // @TableLogic 生成的删除语句自动更新 deleted = 1
        reportMapper.deleteById(report.getId());
    }

    /**
     * 查询当前用户拥有的报告，找不到按 404 处理
     */
    private Report getOwnedReport(Long userId, Long id) {
        Report report = reportMapper.selectOne(
                new QueryWrapper<Report>()
                        .eq("id", id)
                        .eq("user_id", userId)
        );
        if (report == null) {
            throw new BusinessException(404, "报告不存在");
        }
        return report;
    }

    /**
     * 按请求顺序加载当前用户拥有的文献，任一越权按 403 处理
     */
    private List<Document> loadOwnedDocuments(Long userId, List<Long> requestedIds) {
        List<Document> owned = documentAccessService.listOwned(userId, requestedIds);
        Map<Long, Document> ownedMap = new HashMap<>();
        if (owned != null) {
            for (Document document : owned) {
                if (document.getId() != null) {
                    ownedMap.put(document.getId(), document);
                }
            }
        }

        List<Document> documents = new ArrayList<>(requestedIds.size());
        for (Long documentId : requestedIds) {
            Document document = ownedMap.get(documentId);
            if (document != null) {
                documents.add(document);
            }
        }
        if (documents.size() != requestedIds.size()) {
            throw new BusinessException(403, "所选文献不存在或无权访问");
        }
        return documents;
    }

    /**
     * 查询每篇文献 is_current = 1 的分析，优先 FULL_ANALYSIS，其次任意当前类型
     */
    private Map<Long, AiAnalysis> loadCurrentAnalyses(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<AiAnalysis> analyses = aiAnalysisMapper.selectList(
                new QueryWrapper<AiAnalysis>()
                        .in("document_id", documentIds)
                        .eq("is_current", 1)
                        .orderByDesc("id")
        );

        Map<Long, AiAnalysis> selected = new HashMap<>();
        Map<Long, AiAnalysis> fallback = new HashMap<>();
        for (AiAnalysis analysis : analyses) {
            Long documentId = analysis.getDocumentId();
            if (documentId == null) {
                continue;
            }
            if ("FULL_ANALYSIS".equals(analysis.getAnalysisType())) {
                selected.putIfAbsent(documentId, analysis);
            } else {
                fallback.putIfAbsent(documentId, analysis);
            }
        }
        // 没有完整分析的文献，使用任意类型的当前分析兜底
        fallback.forEach(selected::putIfAbsent);
        return selected;
    }

    /**
     * 组装 Markdown 正文
     */
    private String buildContent(
            String title,
            List<Document> documents,
            Map<Long, AiAnalysis> analyses
    ) {
        StringBuilder sb = new StringBuilder();

        // 标题与来源提示
        sb.append("# ").append(title).append("\n\n");
        sb.append("> ").append(REPORT_NOTICE).append("\n\n");

        // 文献概览
        sb.append("## 一、文献概览\n\n");
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            sb.append(i + 1)
                    .append(". ")
                    .append(documentTitle(document))
                    .append("（")
                    .append(dash(document.getJournal()))
                    .append("，")
                    .append(yearText(document))
                    .append("）\n");
        }
        sb.append("\n");

        // 每篇文献一节
        for (Document document : documents) {
            AiAnalysis analysis = analyses.get(document.getId());

            sb.append("## ").append(documentTitle(document)).append("\n\n");
            sb.append("- **作者**：").append(authorsText(document)).append("\n");
            sb.append("- **发表**：")
                    .append(dash(document.getJournal()))
                    .append("，")
                    .append(yearText(document))
                    .append("\n");
            sb.append("- **DOI**：").append(dash(document.getDoi())).append("\n\n");

            appendSection(
                    sb,
                    "研究背景",
                    analysis == null ? null : analysis.getResearchBackground()
            );
            appendSection(
                    sb,
                    "研究方法",
                    analysis == null ? null : analysis.getResearchMethod()
            );
            appendInnovationPoints(sb, analysis);
            appendSection(
                    sb,
                    "实验结果",
                    analysis == null ? null : analysis.getExperimentalResults()
            );
            appendSection(
                    sb,
                    "研究结论",
                    analysis == null ? null : analysis.getConclusion()
            );
        }

        // 引用信息（GB/T 7714 风格）
        sb.append("## 附：引用信息\n\n");
        for (int i = 0; i < documents.size(); i++) {
            sb.append("[").append(i + 1).append("] ")
                    .append(buildCitation(documents.get(i)))
                    .append("\n");
        }
        return sb.toString();
    }

    /**
     * 追加一个三级标题与正文，正文缺失时写入统一提示
     */
    private void appendSection(StringBuilder sb, String heading, String value) {
        sb.append("### ").append(heading).append("\n\n");
        sb.append(hasText(value) ? value.trim() : MISSING_ANALYSIS).append("\n\n");
    }

    /**
     * 追加创新点列表，缺失时写入统一提示
     */
    private void appendInnovationPoints(StringBuilder sb, AiAnalysis analysis) {
        sb.append("### 创新点\n\n");

        List<String> points = analysis == null ? null : analysis.getInnovationPoints();
        boolean appended = false;
        if (points != null) {
            for (String point : points) {
                if (hasText(point)) {
                    sb.append("- ").append(point.trim()).append("\n");
                    appended = true;
                }
            }
        }
        if (!appended) {
            sb.append(MISSING_ANALYSIS).append("\n");
        }
        sb.append("\n");
    }

    /**
     * 生成 GB/T 7714 风格引用
     */
    private String buildCitation(Document document) {
        List<String> authors = authorList(document);
        String firstAuthor = authors.isEmpty() ? "佚名" : authors.get(0);
        String documentType = "JOURNAL".equals(document.getDocumentType()) ? "J" : "C";

        StringBuilder sb = new StringBuilder();
        sb.append(firstAuthor)
                .append(". ")
                .append(documentTitle(document))
                .append("[").append(documentType).append("]. ")
                .append(dash(document.getJournal()))
                .append(", ")
                .append(yearText(document))
                .append(".");
        if (hasText(document.getDoi())) {
            sb.append(" DOI: ").append(document.getDoi().trim()).append(".");
        }
        return sb.toString();
    }

    /**
     * 文献作者列表（过滤空值）
     */
    private List<String> authorList(Document document) {
        List<String> authors = new ArrayList<>();
        if (document == null || document.getAuthors() == null) {
            return authors;
        }
        for (String author : document.getAuthors()) {
            if (hasText(author)) {
                authors.add(author.trim());
            }
        }
        return authors;
    }

    /**
     * 作者展示文本
     */
    private String authorsText(Document document) {
        List<String> authors = authorList(document);
        return authors.isEmpty() ? "—" : String.join(", ", authors);
    }

    /**
     * 文献标题展示文本
     */
    private String documentTitle(Document document) {
        if (document == null || !hasText(document.getTitle())) {
            return "未命名文献";
        }
        return document.getTitle().trim();
    }

    /**
     * 年份展示文本
     */
    private String yearText(Document document) {
        if (document == null || document.getPublicationYear() == null) {
            return "—";
        }
        return String.valueOf(document.getPublicationYear());
    }

    /**
     * 空值占位
     */
    private String dash(String value) {
        return hasText(value) ? value.trim() : "—";
    }

    /**
     * 判断字符串是否有实际内容
     */
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 规范化报告类型
     */
    private String resolveReportType(String reportType) {
        if (reportType == null || reportType.trim().isEmpty()) {
            return DEFAULT_REPORT_TYPE;
        }
        String normalized = reportType.trim().toUpperCase(Locale.ROOT);
        if (!REPORT_TYPES.contains(normalized)) {
            throw new BusinessException(400, "无效的报告类型");
        }
        return normalized;
    }

    /**
     * 规范化导出格式：markdown / md / docx，缺省为 markdown
     */
    private String normalizeExportFormat(String format) {
        if (format == null || format.trim().isEmpty()) {
            return FORMAT_MARKDOWN;
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        if ("md".equals(normalized)) {
            return FORMAT_MARKDOWN;
        }
        if (!FORMAT_MARKDOWN.equals(normalized) && !FORMAT_DOCX.equals(normalized)) {
            throw new BusinessException(400, "不支持的导出格式，仅支持 markdown 或 docx");
        }
        return normalized;
    }

    /**
     * 生成安全的下载文件名（去掉路径分隔符等非法字符，避免超长）
     */
    private String sanitizeFileName(String title) {
        String base = title == null ? "" : title.trim();
        if (base.isEmpty()) {
            return DEFAULT_FILE_NAME;
        }
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_")
                .replaceAll("\\s+", " ")
                .replaceAll("[. ]+$", "")
                .trim();
        if (base.length() > MAX_FILE_NAME_LENGTH) {
            base = base.substring(0, MAX_FILE_NAME_LENGTH).trim();
        }
        return base.isEmpty() ? DEFAULT_FILE_NAME : base;
    }

    /**
     * 文献ID去重、过滤空值，保留请求顺序
     */
    private List<Long> normalizeDocumentIds(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Long documentId : documentIds) {
            if (documentId != null) {
                uniqueIds.add(documentId);
            }
        }
        return new ArrayList<>(uniqueIds);
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
     * 按 documentIds 顺序生成标题列表，缺失的文献以空串占位，保持下标对齐
     */
    private List<String> titlesOf(List<Long> documentIds, Map<Long, String> titleMap) {
        if (documentIds == null || documentIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> titles = new ArrayList<>(documentIds.size());
        for (Long documentId : documentIds) {
            String title = documentId == null || titleMap == null ? null : titleMap.get(documentId);
            titles.add(title == null ? "" : title);
        }
        return titles;
    }
}
