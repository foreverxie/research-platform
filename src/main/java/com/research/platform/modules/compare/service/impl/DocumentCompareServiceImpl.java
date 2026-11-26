package com.research.platform.modules.compare.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.research.platform.common.enums.AnalysisStatus;
import com.research.platform.common.enums.AnalysisType;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.modules.analysis.entity.AiAnalysis;
import com.research.platform.modules.analysis.mapper.AiAnalysisMapper;
import com.research.platform.modules.category.entity.Category;
import com.research.platform.modules.category.mapper.CategoryMapper;
import com.research.platform.modules.compare.dto.CompareRequestDTO;
import com.research.platform.modules.compare.service.IDocumentCompareService;
import com.research.platform.modules.compare.vo.CompareDocumentVO;
import com.research.platform.modules.compare.vo.CompareGapVO;
import com.research.platform.modules.compare.vo.CompareResultVO;
import com.research.platform.modules.compare.vo.CompareRowVO;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.service.DocumentAccessService;
import com.research.platform.utils.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文献对比服务实现
 *
 * <p>对比维度固定为研究问题、研究方法、模型/算法、实验数据集、实验结果、研究贡献、研究局限性。
 * 每个维度标注数据来源：原文（ORIGINAL）、AI 归纳（AI）或未知（UNKNOWN），
 * 使原文事实与 AI 推断在前端可区分。</p>
 */
@Service
public class DocumentCompareServiceImpl implements IDocumentCompareService {

    private static final String SOURCE_ORIGINAL = "ORIGINAL";
    private static final String SOURCE_AI = "AI";
    private static final String SOURCE_UNKNOWN = "UNKNOWN";

    private static final String NOT_STATED = "原文未明确陈述";
    private static final String NOT_ANALYZED = "尚未生成分析结果";
    private static final String EMPTY_VALUE = "—";

    private static final int MIN_DOCUMENTS = 2;
    private static final int MAX_DOCUMENTS = 6;
    private static final int MODEL_KEYWORD_COUNT = 3;
    private static final int MAX_GAP_COUNT = 3;
    private static final int LIMITATION_MAX_LENGTH = 300;

    /**
     * “数据集：XXX” / “数据集为 XXX” 形式的名称（必须有分隔符，避免把“数据集上”的“上”当成名称）
     */
    private static final Pattern DATASET_AFTER =
            Pattern.compile("(?:数据集|dataset|Dataset|benchmark|Benchmark)\\s*[：:=为是]\\s*"
                    + "([A-Za-z0-9\\u4e00-\\u9fa5][A-Za-z0-9\\u4e00-\\u9fa5\\-_]*"
                    + "(?:\\s*[、,，/]\\s*[A-Za-z0-9\\u4e00-\\u9fa5\\-_]+)*)");

    /**
     * “BraTS 2021 数据集”形式的英文数据集名
     */
    private static final Pattern DATASET_LATIN_BEFORE =
            Pattern.compile("([A-Z][A-Za-z0-9\\-]*(?:\\s+[A-Z0-9][A-Za-z0-9\\-]*){0,3})\\s*(?:公开)?数据集");

    /**
     * “医学图像分割数据集”形式的中文数据集名
     */
    private static final Pattern DATASET_CHINESE_BEFORE =
            Pattern.compile("([\\u4e00-\\u9fa5]{2,10})\\s*(?:公开)?数据集");

    /**
     * 兜底：英文专有名词（常见公开数据集名）
     */
    private static final Pattern CAPITALIZED_NAME =
            Pattern.compile("\\b([A-Z][A-Za-z0-9\\-]{2,}(?:\\s+[A-Z][A-Za-z0-9\\-]+)?)\\b");

    /**
     * 候选名称前缀：在 / 使用 / 公开 / 本文 / 的 等修饰词
     */
    private static final Pattern DATASET_PREFIX = Pattern.compile(
            "^(?:本文|该|此|其|现有|以上|在|于|使用|采用|基于|选取|选用|构建|构造|通过|以|对|由|用"
                    + "|公开|大规模|真实|合成|的)+");

    /**
     * 数据集候选词的停用词。用 List 保存：Set.of 遇到重复元素会在类初始化时抛异常
     */
    private static final List<String> DATASET_STOP_WORDS = List.of(
            "the", "this", "that", "these", "those", "our", "we", "in", "on", "for", "with", "and",
            "table", "figure", "fig", "section", "however", "therefore", "results", "result",
            "model", "models", "deep", "learning", "experiments", "experiment", "dataset", "data",
            "these", "both", "each", "their", "its", "all", "two", "three", "first", "second"
    );

    /**
     * 无信息量的候选词
     */
    private static final List<String> DATASET_NOISE_WORDS = List.of(
            "多个", "两个", "三个", "若干", "公开", "真实", "合成", "训练", "测试", "验证",
            "实验", "数据", "本文", "现有", "以上", "该", "此", "其");

    /**
     * 数量 / 修饰前缀：候选以这些词开头时不是数据集名称
     */
    private static final List<String> DATASET_QUANTIFIERS = List.of("多个", "两个", "三个", "若干", "公开");

    private static final Pattern SENTENCE_DELIMITER = Pattern.compile("[。；;！!？?\\n]");

    /**
     * 局限性的提示词：句中出现任意一个即视为局限性表述
     */
    private static final List<String> LIMITATION_MARKERS =
            List.of("局限", "但", "仍", "然而", "不足", "尚未", "有待", "欠缺");

    private final DocumentAccessService documentAccessService;
    private final AiAnalysisMapper aiAnalysisMapper;
    private final CategoryMapper categoryMapper;

    public DocumentCompareServiceImpl(DocumentAccessService documentAccessService,
                                      AiAnalysisMapper aiAnalysisMapper,
                                      CategoryMapper categoryMapper) {
        this.documentAccessService = documentAccessService;
        this.aiAnalysisMapper = aiAnalysisMapper;
        this.categoryMapper = categoryMapper;
    }

    @Override
    public CompareResultVO compare(CompareRequestDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();

        // 1. 参数规范化与数量校验
        List<Long> documentIds = dto.getDocumentIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (documentIds.size() < MIN_DOCUMENTS) {
            throw new BusinessException(400, "请至少选择两篇文献进行对比");
        }
        if (documentIds.size() > MAX_DOCUMENTS) {
            throw new BusinessException(400, "单次最多对比 6 篇文献");
        }

        // 2. 归属校验：documentIds 必须全部属于当前用户
        List<Document> ownedDocuments = documentAccessService.listOwned(userId, documentIds);
        if (ownedDocuments.size() != documentIds.size()) {
            throw new BusinessException(404, "部分文献不存在或无权访问，请刷新后重试");
        }

        Map<Long, Document> documentMap = new HashMap<>();
        for (Document document : ownedDocuments) {
            documentMap.put(document.getId(), document);
        }

        // 保持前端选择顺序
        List<Document> documents = documentIds.stream()
                .map(documentMap::get)
                .filter(Objects::nonNull)
                .toList();

        // 3. 载入当前有效的分析结果与分类名称
        Map<Long, AiAnalysis> analyses = loadCurrentAnalyses(documentIds);
        Map<Long, String> categoryNames = loadCategoryNames(documents);

        CompareResultVO result = new CompareResultVO();
        result.setDocuments(toDocumentVOs(documents, categoryNames));
        result.setRows(buildRows(documents, analyses));
        result.setGaps(buildGaps(documents, analyses, result.getRows()));
        return result;
    }

    /**
     * 取每篇文献当前有效（is_current=1）的分析，优先 FULL_ANALYSIS
     */
    private Map<Long, AiAnalysis> loadCurrentAnalyses(List<Long> documentIds) {
        List<AiAnalysis> analyses = aiAnalysisMapper.selectList(
                new QueryWrapper<AiAnalysis>()
                        .in("document_id", documentIds)
                        .eq("is_current", 1)
        );

        Map<Long, AiAnalysis> result = new HashMap<>();
        for (AiAnalysis analysis : analyses) {
            if (analysis.getDocumentId() == null || !isUsable(analysis)) {
                continue;
            }

            AiAnalysis current = result.get(analysis.getDocumentId());
            if (current == null || prefer(analysis, current)) {
                result.put(analysis.getDocumentId(), analysis);
            }
        }
        return result;
    }

    /**
     * 只使用生成成功的分析结果
     */
    private boolean isUsable(AiAnalysis analysis) {
        return analysis.getStatus() == null
                || AnalysisStatus.SUCCESS.name().equalsIgnoreCase(analysis.getStatus());
    }

    /**
     * FULL_ANALYSIS 优先，其次取较新的版本
     */
    private boolean prefer(AiAnalysis candidate, AiAnalysis current) {
        boolean candidateFull = AnalysisType.FULL_ANALYSIS.name().equalsIgnoreCase(candidate.getAnalysisType());
        boolean currentFull = AnalysisType.FULL_ANALYSIS.name().equalsIgnoreCase(current.getAnalysisType());

        if (candidateFull != currentFull) {
            return candidateFull;
        }
        if (candidate.getId() == null) {
            return false;
        }
        return current.getId() == null || candidate.getId() > current.getId();
    }

    /**
     * 构建固定的七个对比维度
     */
    private List<CompareRowVO> buildRows(List<Document> documents, Map<Long, AiAnalysis> analyses) {
        List<CompareRowVO> rows = new ArrayList<>();

        rows.add(analysisRow("researchProblem", "研究问题", documents, analyses,
                AiAnalysis::getResearchBackground, NOT_STATED));
        rows.add(analysisRow("method", "研究方法", documents, analyses,
                AiAnalysis::getResearchMethod, NOT_STATED));
        rows.add(modelRow(documents));
        rows.add(datasetRow(documents, analyses));
        rows.add(analysisRow("result", "实验结果", documents, analyses,
                AiAnalysis::getExperimentalResults, NOT_ANALYZED));
        rows.add(contributionRow(documents, analyses));
        rows.add(limitationRow(documents, analyses));
        return rows;
    }

    /**
     * AI 分析字段直接构成的维度（研究问题 / 方法 / 结果）
     */
    private CompareRowVO analysisRow(String key,
                                     String label,
                                     List<Document> documents,
                                     Map<Long, AiAnalysis> analyses,
                                     Function<AiAnalysis, String> extractor,
                                     String fallback) {
        List<String> values = new ArrayList<>(documents.size());
        boolean anyPresent = false;

        for (Document document : documents) {
            AiAnalysis analysis = analyses.get(document.getId());
            String value = analysis == null ? null : trimToNull(extractor.apply(analysis));
            if (value == null) {
                values.add(fallback);
            } else {
                anyPresent = true;
                values.add(value);
            }
        }

        // 只要有一篇文献存在 AI 取值，该行即标注为 AI 归纳；全部缺失才标注未知
        return new CompareRowVO(key, label, values, anyPresent ? SOURCE_AI : SOURCE_UNKNOWN);
    }

    /**
     * 模型 / 算法：取文献关键词前 3 个，属于原文信息
     */
    private CompareRowVO modelRow(List<Document> documents) {
        List<String> values = new ArrayList<>(documents.size());

        for (Document document : documents) {
            List<String> keywords = document.getKeywords();
            if (keywords == null || keywords.isEmpty()) {
                values.add(EMPTY_VALUE);
                continue;
            }

            List<String> selected = new ArrayList<>(MODEL_KEYWORD_COUNT);
            for (String keyword : keywords) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                selected.add(keyword.trim());
                if (selected.size() >= MODEL_KEYWORD_COUNT) {
                    break;
                }
            }
            values.add(selected.isEmpty() ? EMPTY_VALUE : String.join("、", selected));
        }

        return new CompareRowVO("model", "模型 / 算法", values, SOURCE_ORIGINAL);
    }

    /**
     * 实验数据集：从 experimentalResults 中简单提取
     */
    private CompareRowVO datasetRow(List<Document> documents, Map<Long, AiAnalysis> analyses) {
        List<String> values = new ArrayList<>(documents.size());
        boolean anyExtracted = false;

        for (Document document : documents) {
            AiAnalysis analysis = analyses.get(document.getId());
            String dataset = analysis == null
                    ? null
                    : extractDataset(analysis.getExperimentalResults());

            if (dataset == null) {
                values.add(NOT_STATED);
            } else {
                anyExtracted = true;
                values.add(dataset);
            }
        }

        return new CompareRowVO("dataset", "实验数据集", values, anyExtracted ? SOURCE_AI : SOURCE_UNKNOWN);
    }

    /**
     * 研究贡献：取分析结果中的创新点
     */
    private CompareRowVO contributionRow(List<Document> documents, Map<Long, AiAnalysis> analyses) {
        List<String> values = new ArrayList<>(documents.size());
        boolean anyPresent = false;

        for (Document document : documents) {
            AiAnalysis analysis = analyses.get(document.getId());
            String contribution = analysis == null
                    ? null
                    : joinNonBlank(analysis.getInnovationPoints(), "；");

            if (contribution == null || contribution.isBlank()) {
                values.add(NOT_ANALYZED);
            } else {
                anyPresent = true;
                values.add(contribution);
            }
        }

        return new CompareRowVO("contribution", "研究贡献", values, anyPresent ? SOURCE_AI : SOURCE_UNKNOWN);
    }

    /**
     * 研究局限性：从结论中含“但/仍/局限”等提示词的句子提取
     */
    private CompareRowVO limitationRow(List<Document> documents, Map<Long, AiAnalysis> analyses) {
        List<String> values = new ArrayList<>(documents.size());
        boolean anyExtracted = false;

        for (Document document : documents) {
            AiAnalysis analysis = analyses.get(document.getId());
            String limitation = analysis == null ? null : extractLimitation(analysis.getConclusion());

            if (limitation == null) {
                values.add(NOT_STATED);
            } else {
                anyExtracted = true;
                values.add(limitation);
            }
        }

        return new CompareRowVO("limitation", "研究局限性", values, anyExtracted ? SOURCE_AI : SOURCE_UNKNOWN);
    }

    /**
     * 根据缺失维度与局限性生成 1-3 条研究空白；无法生成时返回空数组
     */
    private List<CompareGapVO> buildGaps(List<Document> documents,
                                         Map<Long, AiAnalysis> analyses,
                                         List<CompareRowVO> rows) {
        List<CompareGapVO> candidates = new ArrayList<>();

        // 1. 部分文献尚未生成 AI 分析
        List<String> missingAnalysisTitles = new ArrayList<>();
        for (Document document : documents) {
            if (analyses.get(document.getId()) == null) {
                missingAnalysisTitles.add(document.getTitle());
            }
        }
        if (!missingAnalysisTitles.isEmpty()) {
            candidates.add(new CompareGapVO(
                    "补齐缺失的 AI 分析结果",
                    "所选文献中有 " + missingAnalysisTitles.size()
                            + " 篇尚未生成当前有效的 AI 分析，研究问题、方法、结果等维度只能标注为未知，"
                            + "建议先完成全文分析再做跨文献对比。",
                    "依据：文献" + quoteTitles(missingAnalysisTitles)
                            + "缺少 is_current=1 且状态为 SUCCESS 的分析记录"));
        }

        // 2. 数据集披露不完整
        long datasetMissing = countUnknownValues(rows, "dataset");
        if (datasetMissing > 0) {
            candidates.add(new CompareGapVO(
                    "实验数据集披露不完整",
                    "有 " + datasetMissing + " 篇文献的原文与 AI 分析中未出现可识别的数据集名称，"
                            + "跨文献的实验可复现性与结果可比性因此难以判断。",
                    "依据：“实验数据集”维度中有 " + datasetMissing + " 处取值为“" + NOT_STATED + "”"));
        }

        // 3. 局限性共性
        long limitationCount = documents.stream()
                .map(document -> analyses.get(document.getId()))
                .filter(Objects::nonNull)
                .map(analysis -> extractLimitation(analysis.getConclusion()))
                .filter(Objects::nonNull)
                .count();
        if (limitationCount > 0) {
            candidates.add(new CompareGapVO(
                    "研究局限性的共性收敛",
                    "所选文献中有 " + limitationCount
                            + " 篇明确提及研究局限，可围绕这些限制（泛化能力、评价口径、数据规模等）"
                            + "设计后续改进与对照实验。",
                    "依据：从 " + limitationCount
                            + " 篇文献的结论中提取到含“但/仍/局限/不足”等提示词的局限性表述"));
        }

        // 4. 方法路线差异
        long methodVariants = documents.stream()
                .map(document -> analyses.get(document.getId()))
                .filter(Objects::nonNull)
                .map(AiAnalysis::getResearchMethod)
                .map(this::trimToNull)
                .filter(Objects::nonNull)
                .map(method -> method.length() > 40 ? method.substring(0, 40) : method)
                .distinct()
                .count();
        if (methodVariants >= 2) {
            candidates.add(new CompareGapVO(
                    "研究方法路线尚未统一",
                    "所选文献的研究方法可归纳为 " + methodVariants
                            + " 种不同路线，尚缺少在同一数据集与评价口径下的横向对照。",
                    "依据：“研究方法”维度出现 " + methodVariants + " 种不同表述"));
        }

        // 5. 共同主题不足
        long sharedKeywordCount = countSharedKeywords(documents);
        if (sharedKeywordCount <= 1) {
            candidates.add(new CompareGapVO(
                    "跨文献共同研究主题不突出",
                    "所选文献仅在 " + sharedKeywordCount
                            + " 个关键词上重合，主题跨度较大，建议补充同一子方向的综述或代表性工作进行对比。",
                    "依据：对所选文献的 keywords 取交集后仅有 " + sharedKeywordCount + " 个共同关键词"));
        }

        return candidates.stream().limit(MAX_GAP_COUNT).toList();
    }

    /**
     * 统计某维度中“原文未明确陈述”的出现次数
     */
    private long countUnknownValues(List<CompareRowVO> rows, String key) {
        for (CompareRowVO row : rows) {
            if (!key.equals(row.getKey())) {
                continue;
            }

            long count = 0;
            for (String value : row.getValues()) {
                if (NOT_STATED.equals(value)) {
                    count++;
                }
            }
            return count;
        }
        return 0;
    }

    /**
     * 统计所选文献 keywords 的交集大小
     */
    private long countSharedKeywords(List<Document> documents) {
        Set<String> shared = null;

        for (Document document : documents) {
            Set<String> keywords = new LinkedHashSet<>();
            if (document.getKeywords() != null) {
                for (String keyword : document.getKeywords()) {
                    if (keyword != null && !keyword.isBlank()) {
                        keywords.add(keyword.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }

            if (shared == null) {
                shared = keywords;
            } else {
                shared.retainAll(keywords);
            }

            if (shared.isEmpty()) {
                return 0;
            }
        }
        return shared == null ? 0 : shared.size();
    }

    /**
     * 从实验结果的文本中提取数据集名称
     */
    private String extractDataset(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String fromAfter = firstMatch(DATASET_AFTER, text);
        if (fromAfter != null) {
            return fromAfter;
        }

        String latinBefore = firstMatch(DATASET_LATIN_BEFORE, text);
        if (latinBefore != null) {
            return latinBefore;
        }

        String chineseBefore = firstMatch(DATASET_CHINESE_BEFORE, text);
        if (chineseBefore != null) {
            return chineseBefore;
        }

        // 兜底：英文专有名词（如 ImageNet / COCO / BraTS）
        Matcher matcher = CAPITALIZED_NAME.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            String firstWord = candidate.split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (DATASET_STOP_WORDS.contains(firstWord) || candidate.length() < 3) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /**
     * 用指定正则取第一个可用的数据集名称
     */
    private String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String candidate = cleanDatasetName(matcher.group(1));
            if (isMeaningfulDataset(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 清洗数据集名称：去掉“在/使用/公开/的”等修饰前缀
     */
    private String cleanDatasetName(String name) {
        if (name == null) {
            return null;
        }

        String cleaned = name.trim();

        // 中文候选可能带上“公开的医学分割”这类修饰，取最后一个“的”之后的部分
        int lastDeIndex = cleaned.lastIndexOf('的');
        if (lastDeIndex >= 0 && lastDeIndex < cleaned.length() - 1) {
            cleaned = cleaned.substring(lastDeIndex + 1);
        }

        // 逐个剥掉“在/使用/采用”等前缀，直到不再变化
        String previous;
        do {
            previous = cleaned;
            cleaned = DATASET_PREFIX.matcher(cleaned).replaceFirst("").trim();
        } while (!cleaned.equals(previous));

        cleaned = cleaned.replaceAll("[，,、/]+$", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * 排除“多个/公开/训练”等无信息量的候选词
     */
    private boolean isMeaningfulDataset(String candidate) {
        if (candidate == null || candidate.length() < 2) {
            return false;
        }

        String lower = candidate.toLowerCase(Locale.ROOT);
        if (DATASET_STOP_WORDS.contains(lower)) {
            return false;
        }

        // 纯数字（如年份）不是数据集名称
        if (lower.matches("^\\d+$")) {
            return false;
        }

        if (DATASET_NOISE_WORDS.contains(lower)) {
            return false;
        }

        // “两个公开数据集”“三个数据集”这类数量修饰不是数据集名称
        for (String quantifier : DATASET_QUANTIFIERS) {
            if (lower.startsWith(quantifier)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从结论中提取含局限性提示词的句子
     */
    private String extractLimitation(String conclusion) {
        if (conclusion == null || conclusion.isBlank()) {
            return null;
        }

        for (String sentence : SENTENCE_DELIMITER.split(conclusion)) {
            String trimmed = sentence.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            for (String marker : LIMITATION_MARKERS) {
                if (trimmed.contains(marker)) {
                    return truncate(trimmed, LIMITATION_MAX_LENGTH);
                }
            }
        }
        return null;
    }

    /**
     * 文献实体转对比视图
     */
    private List<CompareDocumentVO> toDocumentVOs(List<Document> documents,
                                                  Map<Long, String> categoryNames) {
        List<CompareDocumentVO> result = new ArrayList<>(documents.size());

        for (Document document : documents) {
            CompareDocumentVO vo = new CompareDocumentVO();
            vo.setId(document.getId());
            vo.setTitle(document.getTitle());
            vo.setAuthors(document.getAuthors() == null ? List.of() : document.getAuthors());
            vo.setPublicationYear(document.getPublicationYear());
            vo.setJournal(document.getJournal());
            vo.setCategoryName(document.getCategoryId() == null
                    ? null
                    : categoryNames.get(document.getCategoryId()));
            vo.setKeywords(document.getKeywords() == null ? List.of() : document.getKeywords());
            vo.setDocumentType(document.getDocumentType());
            vo.setParseStatus(document.getParseStatus());
            result.add(vo);
        }
        return result;
    }

    /**
     * 批量载入分类名称
     */
    private Map<Long, String> loadCategoryNames(List<Document> documents) {
        Map<Long, String> categoryNames = new HashMap<>();

        Set<Long> categoryIds = new LinkedHashSet<>();
        for (Document document : documents) {
            if (document.getCategoryId() != null) {
                categoryIds.add(document.getCategoryId());
            }
        }
        if (categoryIds.isEmpty()) {
            return categoryNames;
        }

        for (Category category : categoryMapper.selectBatchIds(categoryIds)) {
            categoryNames.put(category.getId(), category.getName());
        }
        return categoryNames;
    }

    /**
     * 拼接文献标题，用于推断依据
     */
    private String quoteTitles(List<String> titles) {
        StringBuilder builder = new StringBuilder();
        for (String title : titles) {
            if (title == null || title.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("、");
            }
            builder.append("「").append(title.trim()).append("」");
        }
        return builder.isEmpty() ? "（标题为空）" : builder.toString();
    }

    /**
     * 拼接非空字符串列表
     */
    private String joinNonBlank(List<String> values, String separator) {
        if (values == null || values.isEmpty()) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(separator);
            }
            builder.append(value.trim());
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    private String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String text, int maxLength) {
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
