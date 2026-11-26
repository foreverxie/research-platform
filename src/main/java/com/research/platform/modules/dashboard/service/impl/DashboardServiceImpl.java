package com.research.platform.modules.dashboard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.platform.common.enums.TaskStatus;
import com.research.platform.common.vo.TaskVO;
import com.research.platform.modules.category.entity.Category;
import com.research.platform.modules.category.mapper.CategoryMapper;
import com.research.platform.modules.dashboard.service.IDashboardService;
import com.research.platform.modules.dashboard.vo.DashboardStatsVO;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.mapper.DocumentMapper;
import com.research.platform.modules.task.convert.TaskConvert;
import com.research.platform.modules.task.entity.AiTask;
import com.research.platform.modules.task.mapper.AiTaskMapper;
import com.research.platform.utils.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 仪表盘统计服务实现
 *
 * <p>所有统计均按当前用户过滤；总量与本月新增用一条多子查询 SQL 完成，
 * 其余分布类数据使用分组聚合，避免逐篇文献查询造成 N+1。</p>
 */
@Service
public class DashboardServiceImpl implements IDashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardServiceImpl.class);

    /**
     * 一种 SQL 取全部总量与本月新增
     */
    private static final String TOTALS_SQL = """
            SELECT
                (SELECT COUNT(*) FROM document WHERE user_id = ? AND deleted = 0) AS documents,
                (SELECT COUNT(*) FROM category WHERE user_id = ? OR user_id IS NULL) AS categories,
                (SELECT COUNT(*) FROM tag WHERE user_id = ?) AS tags,
                (SELECT COUNT(*) FROM note WHERE user_id = ? AND deleted = 0) AS notes,
                (SELECT COUNT(*) FROM knowledge_card WHERE user_id = ? AND deleted = 0) AS cards,
                (SELECT COUNT(*) FROM report WHERE user_id = ? AND deleted = 0) AS reports,
                (SELECT COUNT(*) FROM conversation WHERE user_id = ? AND deleted = 0) AS conversations,
                (SELECT COUNT(*) FROM document_chunk WHERE user_id = ?) AS chunks,
                (SELECT COUNT(*) FROM ai_task WHERE user_id = ?) AS tasks,
                (SELECT COUNT(*) FROM document WHERE user_id = ? AND deleted = 0
                    AND created_at >= ?) AS month_documents,
                (SELECT COUNT(*) FROM note WHERE user_id = ? AND deleted = 0
                    AND created_at >= ?) AS month_notes,
                (SELECT COUNT(*) FROM ai_task WHERE user_id = ? AND created_at >= ?) AS month_tasks
            """;

    /**
     * 文献类型中文映射（顺序即前端展示顺序）
     */
    private static final Map<String, String> DOCUMENT_TYPE_LABELS = new LinkedHashMap<>();

    static {
        DOCUMENT_TYPE_LABELS.put("JOURNAL", "期刊论文");
        DOCUMENT_TYPE_LABELS.put("CONFERENCE", "会议论文");
        DOCUMENT_TYPE_LABELS.put("THESIS", "学位论文");
        DOCUMENT_TYPE_LABELS.put("PREPRINT", "预印本");
        DOCUMENT_TYPE_LABELS.put("BOOK", "专著");
        DOCUMENT_TYPE_LABELS.put("OTHER", "其他");
    }

    private final JdbcTemplate jdbcTemplate;
    private final DocumentMapper documentMapper;
    private final CategoryMapper categoryMapper;
    private final AiTaskMapper aiTaskMapper;
    private final ObjectMapper objectMapper;

    public DashboardServiceImpl(
            JdbcTemplate jdbcTemplate,
            DocumentMapper documentMapper,
            CategoryMapper categoryMapper,
            AiTaskMapper aiTaskMapper,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentMapper = documentMapper;
        this.categoryMapper = categoryMapper;
        this.aiTaskMapper = aiTaskMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public DashboardStatsVO getStats() {
        Long userId = CurrentUser.getCurrentUserId();
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        DashboardStatsVO stats = new DashboardStatsVO();
        fillTotals(stats, userId, monthStart);
        stats.setTaskSummary(buildTaskSummary(userId));
        stats.setParseSummary(buildParseSummary(userId));
        stats.setTrend(buildTrend(userId));
        stats.setTypeDistribution(buildTypeDistribution(userId));
        stats.setKeywordTop(buildKeywordTop(userId));
        stats.setRecentDocuments(buildRecentDocuments(userId));
        stats.setRunningTasks(buildRunningTasks(userId));
        stats.setAiUsage(buildAiUsage(userId));

        return stats;
    }

    /* ------------------------------------------------------------------ */
    /* 总量与本月新增                                                     */
    /* ------------------------------------------------------------------ */

    private void fillTotals(DashboardStatsVO stats, Long userId, LocalDateTime monthStart) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                TOTALS_SQL,
                userId, userId, userId, userId, userId, userId, userId, userId, userId,
                userId, monthStart,
                userId, monthStart,
                userId, monthStart
        );

        DashboardStatsVO.Totals totals = new DashboardStatsVO.Totals();
        totals.setDocuments(count(row, "documents"));
        totals.setCategories(count(row, "categories"));
        totals.setTags(count(row, "tags"));
        totals.setNotes(count(row, "notes"));
        totals.setCards(count(row, "cards"));
        totals.setReports(count(row, "reports"));
        totals.setConversations(count(row, "conversations"));
        totals.setChunks(count(row, "chunks"));
        totals.setTasks(count(row, "tasks"));
        stats.setTotals(totals);

        DashboardStatsVO.MonthNew monthNew = new DashboardStatsVO.MonthNew();
        monthNew.setDocuments(count(row, "month_documents"));
        monthNew.setNotes(count(row, "month_notes"));
        monthNew.setTasks(count(row, "month_tasks"));
        stats.setMonthNew(monthNew);
    }

    /* ------------------------------------------------------------------ */
    /* 状态分布                                                           */
    /* ------------------------------------------------------------------ */

    private Map<String, Long> buildTaskSummary(Long userId) {
        Map<String, Long> summary = emptyCounts(
                TaskStatus.PENDING.name(),
                TaskStatus.PROCESSING.name(),
                TaskStatus.SUCCESS.name(),
                TaskStatus.FAILED.name(),
                TaskStatus.CANCELLED.name()
        );

        jdbcTemplate.query(
                "SELECT status, COUNT(*) AS cnt FROM ai_task WHERE user_id = ? GROUP BY status",
                (RowCallbackHandler) rs -> {
                    String status = rs.getString("status");
                    if (summary.containsKey(status)) {
                        summary.put(status, rs.getLong("cnt"));
                    }
                },
                userId
        );

        return summary;
    }

    private Map<String, Long> buildParseSummary(Long userId) {
        Map<String, Long> summary = emptyCounts("PENDING", "PROCESSING", "SUCCESS", "FAILED");

        jdbcTemplate.query(
                "SELECT parse_status, COUNT(*) AS cnt FROM document "
                        + "WHERE user_id = ? AND deleted = 0 GROUP BY parse_status",
                (RowCallbackHandler) rs -> {
                    String status = rs.getString("parse_status");
                    if (summary.containsKey(status)) {
                        summary.put(status, rs.getLong("cnt"));
                    }
                },
                userId
        );

        return summary;
    }

    /* ------------------------------------------------------------------ */
    /* 趋势、类型、关键词                                                 */
    /* ------------------------------------------------------------------ */

    private List<DashboardStatsVO.TrendItem> buildTrend(Long userId) {
        LocalDateTime start = LocalDate.now().minusMonths(11).withDayOfMonth(1).atStartOfDay();

        Map<String, Long> counts = new HashMap<>();
        jdbcTemplate.query(
                "SELECT DATE_FORMAT(created_at, '%Y-%m') AS ym, COUNT(*) AS cnt "
                        + "FROM document WHERE user_id = ? AND deleted = 0 AND created_at >= ? "
                        + "GROUP BY ym",
                (RowCallbackHandler) rs -> counts.put(rs.getString("ym"), rs.getLong("cnt")),
                userId, start
        );

        List<DashboardStatsVO.TrendItem> trend = new ArrayList<>(12);
        YearMonth current = YearMonth.now();
        for (int i = 11; i >= 0; i--) {
            YearMonth yearMonth = current.minusMonths(i);
            DashboardStatsVO.TrendItem item = new DashboardStatsVO.TrendItem();
            item.setMonth(yearMonth.getMonthValue() + "月");
            item.setCount(counts.getOrDefault(yearMonth.toString(), 0L));
            trend.add(item);
        }
        return trend;
    }

    private List<DashboardStatsVO.NameValue> buildTypeDistribution(Long userId) {
        Map<String, Long> counts = new LinkedHashMap<>();

        jdbcTemplate.query(
                "SELECT document_type, COUNT(*) AS cnt FROM document "
                        + "WHERE user_id = ? AND deleted = 0 GROUP BY document_type",
                (RowCallbackHandler) rs -> {
                    String type = rs.getString("document_type");
                    String normalized = type != null && DOCUMENT_TYPE_LABELS.containsKey(type.trim())
                            ? type.trim()
                            : "OTHER";
                    counts.merge(normalized, rs.getLong("cnt"), Long::sum);
                },
                userId
        );

        List<DashboardStatsVO.NameValue> distribution = new ArrayList<>();
        DOCUMENT_TYPE_LABELS.forEach((type, label) -> {
            Long value = counts.get(type);
            if (value != null && value > 0) {
                distribution.add(nameValue(label, value));
            }
        });
        return distribution;
    }

    private List<DashboardStatsVO.NameValue> buildKeywordTop(Long userId) {
        // keywords 为 JSON 数组，直接在数据库侧聚合不便，这里只取该字段一次
        List<String> keywordJsonList = jdbcTemplate.queryForList(
                "SELECT keywords FROM document "
                        + "WHERE user_id = ? AND deleted = 0 AND keywords IS NOT NULL",
                String.class,
                userId
        );

        Map<String, Long> counts = new LinkedHashMap<>();
        for (String keywordJson : keywordJsonList) {
            try {
                List<String> keywords = objectMapper.readValue(
                        keywordJson, new TypeReference<List<String>>() {
                        });
                for (String keyword : keywords) {
                    if (keyword != null && !keyword.isBlank()) {
                        counts.merge(keyword.trim(), 1L, Long::sum);
                    }
                }
            } catch (Exception e) {
                log.warn("解析文献关键词 JSON 失败：{}", e.getMessage());
            }
        }

        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(8)
                .map(entry -> nameValue(entry.getKey(), entry.getValue()))
                .toList();
    }

    /* ------------------------------------------------------------------ */
    /* 最近文献与进行中任务                                               */
    /* ------------------------------------------------------------------ */

    private List<DashboardStatsVO.RecentDocument> buildRecentDocuments(Long userId) {
        List<Document> documents = documentMapper.selectList(new QueryWrapper<Document>()
                .eq("user_id", userId)
                .orderByDesc("created_at")
                .orderByDesc("id")
                .last("LIMIT 5"));

        if (documents.isEmpty()) {
            return List.of();
        }

        Map<Long, String> categoryNames = loadCategoryNames(documents.stream()
                .map(Document::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));

        return documents.stream().map(document -> {
            DashboardStatsVO.RecentDocument item = new DashboardStatsVO.RecentDocument();
            item.setId(document.getId());
            item.setTitle(document.getTitle());
            item.setAuthors(document.getAuthors() == null ? List.of() : document.getAuthors());
            item.setJournal(document.getJournal());
            item.setPublicationYear(document.getPublicationYear());
            item.setParseStatus(document.getParseStatus());
            item.setCreatedAt(document.getCreatedAt());
            item.setCategoryName(document.getCategoryId() == null
                    ? null
                    : categoryNames.get(document.getCategoryId()));
            return item;
        }).toList();
    }

    private List<TaskVO> buildRunningTasks(Long userId) {
        List<AiTask> tasks = aiTaskMapper.selectList(new QueryWrapper<AiTask>()
                .eq("user_id", userId)
                .in("status", TaskStatus.PENDING.name(), TaskStatus.PROCESSING.name())
                .orderByDesc("created_at")
                .orderByDesc("id")
                .last("LIMIT 5"));

        if (tasks.isEmpty()) {
            return List.of();
        }

        Map<Long, String> documentTitles = loadDocumentTitles(tasks.stream()
                .map(AiTask::getDocumentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));

        return tasks.stream().map(task -> {
            TaskVO vo = TaskConvert.toVO(task);
            if (task.getDocumentId() != null) {
                vo.setDocumentTitle(documentTitles.get(task.getDocumentId()));
            }
            return vo;
        }).toList();
    }

    /* ------------------------------------------------------------------ */
    /* AI 用量                                                            */
    /* ------------------------------------------------------------------ */

    private List<DashboardStatsVO.AiUsageItem> buildAiUsage(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(6);

        // model_call_log 只有 task_id，需要关联 ai_task 才能按用户统计
        Map<String, long[]> counts = new HashMap<>();
        jdbcTemplate.query(
                "SELECT DATE_FORMAT(l.created_at, '%Y-%m-%d') AS d, COUNT(*) AS calls, "
                        + "COALESCE(SUM(COALESCE(l.request_tokens, 0) + COALESCE(l.response_tokens, 0)), 0) AS tokens "
                        + "FROM model_call_log l JOIN ai_task t ON t.id = l.task_id "
                        + "WHERE t.user_id = ? AND l.created_at >= ? GROUP BY d",
                (RowCallbackHandler) rs -> counts.put(
                        rs.getString("d"),
                        new long[]{rs.getLong("calls"), rs.getLong("tokens")}
                ),
                userId, startDate.atStartOfDay()
        );

        List<DashboardStatsVO.AiUsageItem> usage = new ArrayList<>(7);
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            long[] values = counts.getOrDefault(date.toString(), new long[]{0L, 0L});

            DashboardStatsVO.AiUsageItem item = new DashboardStatsVO.AiUsageItem();
            item.setDate(date.getMonthValue() + "/" + date.getDayOfMonth());
            item.setCalls(values[0]);
            item.setTokens(values[1]);
            usage.add(item);
        }
        return usage;
    }

    /* ------------------------------------------------------------------ */
    /* 辅助方法                                                           */
    /* ------------------------------------------------------------------ */

    private long count(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Map<String, Long> emptyCounts(String... keys) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String key : keys) {
            counts.put(key, 0L);
        }
        return counts;
    }

    private DashboardStatsVO.NameValue nameValue(String name, Long value) {
        DashboardStatsVO.NameValue item = new DashboardStatsVO.NameValue();
        item.setName(name);
        item.setValue(value);
        return item;
    }

    private Map<Long, String> loadCategoryNames(Set<Long> categoryIds) {
        if (categoryIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Category> categories = categoryMapper.selectBatchIds(categoryIds);
        return categories.stream()
                .filter(category -> category.getId() != null && category.getName() != null)
                .collect(Collectors.toMap(Category::getId, Category::getName, (left, right) -> left));
    }

    private Map<Long, String> loadDocumentTitles(Set<Long> documentIds) {
        if (documentIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Document> documents = documentMapper.selectBatchIds(documentIds);
        return documents.stream()
                .filter(document -> document.getId() != null && document.getTitle() != null)
                .collect(Collectors.toMap(Document::getId, Document::getTitle, (left, right) -> left));
    }
}
