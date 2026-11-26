package com.research.platform.modules.dashboard.vo;

import com.research.platform.common.vo.TaskVO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 首页统计数据视图
 *
 * <p>字段与前端 {@code DashboardStats} 严格对齐，均按当前登录用户口径统计。</p>
 */
@Data
public class DashboardStatsVO {

    /**
     * 总量统计
     */
    private Totals totals;

    /**
     * 本月新增
     */
    private MonthNew monthNew;

    /**
     * 任务状态分布：PENDING/PROCESSING/SUCCESS/FAILED/CANCELLED
     */
    private Map<String, Long> taskSummary;

    /**
     * 解析状态分布：PENDING/PROCESSING/SUCCESS/FAILED
     */
    private Map<String, Long> parseSummary;

    /**
     * 近 12 个月文献新增趋势
     */
    private List<TrendItem> trend;

    /**
     * 文献类型分布
     */
    private List<NameValue> typeDistribution;

    /**
     * 高频关键词 Top 8
     */
    private List<NameValue> keywordTop;

    /**
     * 最近入库文献
     */
    private List<RecentDocument> recentDocuments;

    /**
     * 进行中的任务
     */
    private List<TaskVO> runningTasks;

    /**
     * 近 7 天 AI 调用量
     */
    private List<AiUsageItem> aiUsage;

    /**
     * 总量
     */
    @Data
    public static class Totals {

        private Long documents;

        private Long categories;

        private Long tags;

        private Long notes;

        private Long cards;

        private Long reports;

        private Long conversations;

        private Long chunks;

        private Long tasks;
    }

    /**
     * 本月新增
     */
    @Data
    public static class MonthNew {

        private Long documents;

        private Long notes;

        private Long tasks;
    }

    /**
     * 趋势项
     */
    @Data
    public static class TrendItem {

        /**
         * 形如 "11月"
         */
        private String month;

        private Long count;
    }

    /**
     * 通用名称-数值项（文献类型、关键词）
     */
    @Data
    public static class NameValue {

        private String name;

        private Long value;
    }

    /**
     * 最近文献（首页卡片所需的最小字段集）
     */
    @Data
    public static class RecentDocument {

        private Long id;

        private String title;

        private List<String> authors;

        private String journal;

        private Integer publicationYear;

        private String parseStatus;

        private LocalDateTime createdAt;

        private String categoryName;
    }

    /**
     * AI 调用量
     */
    @Data
    public static class AiUsageItem {

        /**
         * 形如 "11/25"
         */
        private String date;

        private Long calls;

        private Long tokens;
    }
}
