package com.research.platform.modules.search.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.research.platform.infrastructure.ai.AiCommands;
import com.research.platform.infrastructure.ai.AiResults;
import com.research.platform.infrastructure.ai.AiServiceClient;
import com.research.platform.modules.category.entity.Category;
import com.research.platform.modules.category.mapper.CategoryMapper;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.entity.DocumentChunk;
import com.research.platform.modules.document.mapper.DocumentChunkMapper;
import com.research.platform.modules.document.mapper.DocumentMapper;
import com.research.platform.modules.document.service.DocumentAccessService;
import com.research.platform.modules.search.dto.SearchRequestDTO;
import com.research.platform.modules.search.service.ISearchService;
import com.research.platform.modules.search.vo.SearchChunkVO;
import com.research.platform.modules.search.vo.SearchDocumentVO;
import com.research.platform.modules.search.vo.SearchHighlightVO;
import com.research.platform.modules.search.vo.SearchHitVO;
import com.research.platform.modules.search.vo.SearchResponseVO;
import com.research.platform.utils.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 智能检索服务实现
 *
 * <p>关键词检索走 MySQL 模糊匹配；语义与混合检索依赖 FastAPI 的向量检索，
 * AI 服务不可用时自动降级为关键词检索，并在 notice 中说明。</p>
 */
@Service
public class SearchServiceImpl implements ISearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchServiceImpl.class);

    private static final String MODE_KEYWORD = "keyword";
    private static final String MODE_SEMANTIC = "semantic";
    private static final String MODE_HYBRID = "hybrid";

    /**
     * 单次返回的条数上限；前端检索页不传 size，默认按上限返回（不做强制分页）
     */
    private static final int MAX_SIZE = 50;

    private static final int DEFAULT_SIZE = MAX_SIZE;

    /**
     * 单次参与检索的文献上限
     */
    private static final int MAX_DOCUMENTS = 500;

    /**
     * 关键词模式扫描的文献上限
     */
    private static final int KEYWORD_SCAN_LIMIT = 200;

    /**
     * 语义检索请求的 topK
     */
    private static final int SEMANTIC_TOP_K = 20;

    /**
     * 命中字段的权重：标题 / 摘要 / 关键词 / 作者
     */
    private static final double WEIGHT_TITLE = 0.45;
    private static final double WEIGHT_ABSTRACT = 0.30;
    private static final double WEIGHT_KEYWORD = 0.20;
    private static final double WEIGHT_AUTHOR = 0.05;

    private static final String SEMANTIC_FALLBACK_NOTICE =
            "语义检索暂不可用（AI 服务未部署），已降级为关键词检索结果。";

    private static final String EMPTY_NOTICE =
            "当前文献库中没有与查询语义相近的内容。请确认相关文献已完成解析后再试。";

    private static final String HIGHLIGHT_TITLE = "标题";
    private static final String HIGHLIGHT_ABSTRACT = "摘要";
    private static final String HIGHLIGHT_KEYWORD = "关键词";
    private static final String HIGHLIGHT_AUTHOR = "作者";

    private static final int ABSTRACT_HIGHLIGHT_LENGTH = 150;

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final CategoryMapper categoryMapper;
    private final DocumentAccessService documentAccessService;
    private final AiServiceClient aiServiceClient;

    public SearchServiceImpl(DocumentMapper documentMapper,
                             DocumentChunkMapper documentChunkMapper,
                             CategoryMapper categoryMapper,
                             DocumentAccessService documentAccessService,
                             AiServiceClient aiServiceClient) {
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.categoryMapper = categoryMapper;
        this.documentAccessService = documentAccessService;
        this.aiServiceClient = aiServiceClient;
    }

    @Override
    public SearchResponseVO search(SearchRequestDTO dto) {
        long started = System.nanoTime();

        Long userId = CurrentUser.getCurrentUserId();
        String query = dto.getQuery().trim();
        String mode = normalizeMode(dto.getMode());

        // 1. 文献范围：documentIds 必须经归属校验，为空表示个人文献库全部文献
        List<Long> scopeIds = resolveScope(userId, dto.getDocumentIds());
        Map<Long, Document> documentMap = loadDocuments(userId, scopeIds);
        Map<Long, String> categoryNames = loadCategoryNames(documentMap.values());

        // 2. 关键词检索始终执行：既是 keyword 模式的结果，也是语义不可用时的兜底
        List<SearchHitVO> keywordHits = keywordHits(userId, query, scopeIds, categoryNames);
        for (SearchHitVO hit : keywordHits) {
            if (hit.getDocument().getCategoryId() != null
                    && !categoryNames.containsKey(hit.getDocument().getCategoryId())) {
                hit.getDocument().setCategoryName(
                        categoryNameOf(hit.getDocument().getCategoryId(), categoryNames));
            }
        }

        // 3. 按模式组装结果
        String notice = null;
        List<SearchHitVO> records;

        if (MODE_KEYWORD.equals(mode)) {
            records = keywordHits;
        } else {
            List<SearchHitVO> semanticHits = null;
            try {
                semanticHits = semanticHits(userId, query, scopeIds, documentMap, categoryNames);
            } catch (Exception e) {
                log.warn("语义检索不可用，已降级为关键词检索：{}", e.getMessage());
                notice = SEMANTIC_FALLBACK_NOTICE;
            }

            if (semanticHits == null) {
                records = keywordHits;
            } else if (MODE_SEMANTIC.equals(mode)) {
                records = semanticHits;
            } else {
                records = merge(keywordHits, semanticHits);
            }
        }

        // 4. 按相关度排序并截断
        records.sort(Comparator.comparingDouble(
                (SearchHitVO hit) -> safeScore(hit.getScore())).reversed());

        int size = normalizeSize(dto.getSize());
        if (records.size() > size) {
            records = new ArrayList<>(records.subList(0, size));
        }

        if (records.isEmpty() && notice == null) {
            notice = EMPTY_NOTICE;
        }

        SearchResponseVO response = new SearchResponseVO();
        response.setMode(mode);
        response.setTook((int) ((System.nanoTime() - started) / 1_000_000L));
        response.setTotal(records.size());
        response.setRecords(records);
        response.setNotice(notice);
        return response;
    }

    /**
     * 规范化检索模式
     */
    private String normalizeMode(String mode) {
        if (mode == null) {
            return MODE_HYBRID;
        }

        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case MODE_KEYWORD, MODE_SEMANTIC, MODE_HYBRID -> normalized;
            default -> MODE_HYBRID;
        };
    }

    /**
     * 规范化返回条数（1-50）
     */
    private int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    /**
     * 解析文献范围：为空返回 null（全库），否则返回经归属校验后的文献ID
     */
    private List<Long> resolveScope(Long userId, List<Long> requestedDocumentIds) {
        if (requestedDocumentIds == null || requestedDocumentIds.isEmpty()) {
            return null;
        }

        List<Long> requestedIds = requestedDocumentIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (requestedIds.isEmpty()) {
            return List.of();
        }

        return documentAccessService.listOwned(userId, requestedIds).stream()
                .map(Document::getId)
                .toList();
    }

    /**
     * 载入当前用户可检索的文献
     */
    private Map<Long, Document> loadDocuments(Long userId, List<Long> scopeIds) {
        Map<Long, Document> documents = new LinkedHashMap<>();
        if (scopeIds != null && scopeIds.isEmpty()) {
            return documents;
        }

        QueryWrapper<Document> wrapper = new QueryWrapper<Document>().eq("user_id", userId);
        if (scopeIds != null) {
            wrapper.in("id", scopeIds);
        }
        wrapper.orderByDesc("updated_at").last("LIMIT " + MAX_DOCUMENTS);

        for (Document document : documentMapper.selectList(wrapper)) {
            documents.put(document.getId(), document);
        }
        return documents;
    }

    /**
     * 批量载入分类名称
     */
    private Map<Long, String> loadCategoryNames(Collection<Document> documents) {
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
     * 取分类名称（缓存未命中时补查）
     */
    private String categoryNameOf(Long categoryId, Map<Long, String> cache) {
        if (categoryId == null) {
            return null;
        }
        if (!cache.containsKey(categoryId)) {
            Category category = categoryMapper.selectById(categoryId);
            cache.put(categoryId, category == null ? null : category.getName());
        }
        return cache.get(categoryId);
    }

    /**
     * 关键词检索：先按 SQL 模糊匹配缩小范围，再按字段命中覆盖率打分
     */
    private List<SearchHitVO> keywordHits(Long userId,
                                          String query,
                                          List<Long> scopeIds,
                                          Map<Long, String> categoryNames) {
        List<String> terms = tokenize(query);
        if (terms.isEmpty() || (scopeIds != null && scopeIds.isEmpty())) {
            return new ArrayList<>();
        }

        QueryWrapper<Document> wrapper = new QueryWrapper<Document>().eq("user_id", userId);
        if (scopeIds != null) {
            wrapper.in("id", scopeIds);
        }
        wrapper.and(condition -> {
            for (int index = 0; index < terms.size(); index++) {
                if (index > 0) {
                    condition.or();
                }
                String term = terms.get(index);
                condition.nested(inner -> inner
                        .like("title", term)
                        .or().like("abstract", term)
                        .or().like("keywords", term)
                        .or().like("authors", term));
            }
        });
        wrapper.last("LIMIT " + KEYWORD_SCAN_LIMIT);

        List<SearchHitVO> hits = new ArrayList<>();
        for (Document document : documentMapper.selectList(wrapper)) {
            double titleCoverage = coverage(document.getTitle(), terms);
            double abstractCoverage = coverage(document.getAbstractText(), terms);
            double keywordCoverage = coverage(join(document.getKeywords(), " "), terms);
            double authorCoverage = coverage(join(document.getAuthors(), " "), terms);

            double score = WEIGHT_TITLE * titleCoverage
                    + WEIGHT_ABSTRACT * abstractCoverage
                    + WEIGHT_KEYWORD * keywordCoverage
                    + WEIGHT_AUTHOR * authorCoverage;

            if (score <= 0) {
                continue;
            }

            SearchHitVO hit = new SearchHitVO();
            hit.setDocument(toDocumentVO(document, categoryNames));
            hit.setScore(round(score));
            hit.setHighlights(buildHighlights(
                    document, titleCoverage, abstractCoverage, keywordCoverage, authorCoverage));
            hits.add(hit);
        }
        return hits;
    }

    /**
     * 语义检索：调用 AI 服务，把 chunk 级命中按文献聚合
     */
    private List<SearchHitVO> semanticHits(Long userId,
                                           String query,
                                           List<Long> scopeIds,
                                           Map<Long, Document> documentMap,
                                           Map<Long, String> categoryNames) {
        if (documentMap.isEmpty()) {
            return new ArrayList<>();
        }

        // 只允许当前有效解析版本的向量参与检索
        List<Long> parseRecordIds = documentMap.values().stream()
                .map(Document::getCurrentParseRecordId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        AiResults.Search search = aiServiceClient.semanticSearch(
                new AiCommands.SemanticSearch(
                        userId, query, scopeIds, parseRecordIds, SEMANTIC_TOP_K));

        if (search == null || search.hits() == null || search.hits().isEmpty()) {
            return new ArrayList<>();
        }

        // 回查 MySQL：只信任属于当前用户、且属于当前有效解析版本的文本块
        List<String> chunkIds = search.hits().stream()
                .filter(Objects::nonNull)
                .map(AiResults.Hit::chunkId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<String, DocumentChunk> chunkMap = new HashMap<>();
        if (!chunkIds.isEmpty()) {
            List<DocumentChunk> storedChunks = documentChunkMapper.selectList(
                    new LambdaQueryWrapper<DocumentChunk>()
                            .in(DocumentChunk::getChunkId, chunkIds)
                            .eq(DocumentChunk::getUserId, userId));
            for (DocumentChunk chunk : storedChunks) {
                chunkMap.put(chunk.getChunkId(), chunk);
            }
        }

        Map<Long, List<AiResults.Hit>> hitsByDocument = new LinkedHashMap<>();
        for (AiResults.Hit hit : search.hits()) {
            if (hit == null || hit.documentId() == null || hit.chunkId() == null) {
                continue;
            }
            // 只保留当前用户可见的文献，避免越权返回
            Document document = documentMap.get(hit.documentId());
            if (document == null) {
                continue;
            }
            DocumentChunk chunk = chunkMap.get(hit.chunkId());
            if (chunk == null
                    || !Objects.equals(chunk.getDocumentId(), hit.documentId())
                    || !Objects.equals(chunk.getParseRecordId(), document.getCurrentParseRecordId())) {
                continue;
            }
            hitsByDocument.computeIfAbsent(hit.documentId(), key -> new ArrayList<>()).add(hit);
        }

        List<SearchHitVO> results = new ArrayList<>();
        for (Map.Entry<Long, List<AiResults.Hit>> entry : hitsByDocument.entrySet()) {
            Document document = documentMap.get(entry.getKey());
            if (document == null) {
                continue;
            }

            List<AiResults.Hit> chunkHits = entry.getValue().stream()
                    .sorted(Comparator.comparingDouble(
                            (AiResults.Hit hit) -> clamp(hit.score())).reversed())
                    .limit(2)
                    .toList();

            double bestScore = chunkHits.stream()
                    .mapToDouble(hit -> clamp(hit.score()))
                    .max()
                    .orElse(0.0);

            SearchHitVO hit = new SearchHitVO();
            hit.setDocument(toDocumentVO(document, categoryNames));
            hit.setScore(round(bestScore));

            List<SearchChunkVO> chunks = new ArrayList<>();
            for (AiResults.Hit chunkHit : chunkHits) {
                DocumentChunk stored = chunkMap.get(chunkHit.chunkId());
                if (stored == null) {
                    continue;
                }
                SearchChunkVO chunk = new SearchChunkVO();
                chunk.setChunkId(stored.getChunkId());
                chunk.setDocumentId(stored.getDocumentId());
                chunk.setSection(stored.getSection());
                chunk.setPageStart(stored.getPageStart());
                chunk.setPageEnd(stored.getPageEnd());
                chunk.setContent(stored.getContent());
                chunk.setScore(round(clamp(chunkHit.score())));
                chunks.add(chunk);
            }

            if (chunks.isEmpty()) {
                continue;
            }

            hit.setChunks(chunks);
            results.add(hit);
        }
        return results;
    }

    /**
     * 混合检索：关键词与语义结果按 documentId 合并，分数各取一半
     */
    private List<SearchHitVO> merge(List<SearchHitVO> keywordHits, List<SearchHitVO> semanticHits) {
        Map<Long, SearchHitVO> merged = new LinkedHashMap<>();

        for (SearchHitVO hit : keywordHits) {
            merged.put(hit.getDocument().getId(), hit);
        }

        for (SearchHitVO semanticHit : semanticHits) {
            Long documentId = semanticHit.getDocument().getId();
            SearchHitVO keywordHit = merged.get(documentId);
            if (keywordHit == null) {
                merged.put(documentId, semanticHit);
                continue;
            }

            // 两种检索都命中：分数加权，保留关键词命中片段与语义片段
            double score = 0.5 * safeScore(keywordHit.getScore()) + 0.5 * safeScore(semanticHit.getScore());
            keywordHit.setScore(round(score));
            keywordHit.setChunks(semanticHit.getChunks());
            if (keywordHit.getHighlights().isEmpty()) {
                keywordHit.setHighlights(semanticHit.getHighlights());
            }
        }

        return new ArrayList<>(merged.values());
    }

    /**
     * 组装命中片段
     */
    private List<SearchHighlightVO> buildHighlights(Document document,
                                                    double titleCoverage,
                                                    double abstractCoverage,
                                                    double keywordCoverage,
                                                    double authorCoverage) {
        List<SearchHighlightVO> highlights = new ArrayList<>();

        if (titleCoverage > 0) {
            highlights.add(new SearchHighlightVO(HIGHLIGHT_TITLE, document.getTitle()));
        }
        if (abstractCoverage > 0 && document.getAbstractText() != null) {
            String text = document.getAbstractText().trim();
            highlights.add(new SearchHighlightVO(
                    HIGHLIGHT_ABSTRACT,
                    text.length() > ABSTRACT_HIGHLIGHT_LENGTH
                            ? text.substring(0, ABSTRACT_HIGHLIGHT_LENGTH) + "…"
                            : text));
        }
        if (keywordCoverage > 0) {
            highlights.add(new SearchHighlightVO(HIGHLIGHT_KEYWORD, join(document.getKeywords(), "、")));
        }
        if (authorCoverage > 0) {
            highlights.add(new SearchHighlightVO(HIGHLIGHT_AUTHOR, join(document.getAuthors(), "、")));
        }
        return highlights;
    }

    /**
     * 文献实体转检索视图
     */
    private SearchDocumentVO toDocumentVO(Document document, Map<Long, String> categoryNames) {
        SearchDocumentVO vo = new SearchDocumentVO();
        vo.setId(document.getId());
        vo.setTitle(document.getTitle());
        vo.setAuthors(safeList(document.getAuthors()));
        vo.setAbstractText(document.getAbstractText());
        vo.setKeywords(safeList(document.getKeywords()));
        vo.setPublicationYear(document.getPublicationYear());
        vo.setJournal(document.getJournal());
        vo.setDocumentType(document.getDocumentType());
        vo.setCategoryId(document.getCategoryId());
        vo.setCategoryName(document.getCategoryId() == null
                ? null
                : categoryNames.get(document.getCategoryId()));
        vo.setParseStatus(document.getParseStatus());
        vo.setCurrentParseRecordId(document.getCurrentParseRecordId());
        return vo;
    }

    /**
     * 字段命中覆盖率：命中词项数 / 词项总数
     */
    private double coverage(String text, List<String> terms) {
        if (text == null || text.isBlank() || terms.isEmpty()) {
            return 0;
        }

        String lowerText = text.toLowerCase(Locale.ROOT);
        int hitCount = 0;
        for (String term : terms) {
            if (lowerText.contains(term)) {
                hitCount++;
            }
        }
        return (double) hitCount / terms.size();
    }

    /**
     * 切分检索词（最多 5 个），统一转小写
     */
    private List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        List<String> terms = new ArrayList<>();
        for (String token : query.trim().split("\\s+")) {
            String term = token.replaceAll("^[,，。;；、:：\\[\\]()（）\"']+|[,，。;；、:：\\[\\]()（）\"']+$", "")
                    .trim()
                    .toLowerCase(Locale.ROOT);
            if (!term.isEmpty() && !terms.contains(term)) {
                terms.add(term);
            }
            if (terms.size() >= 5) {
                break;
            }
        }
        return terms;
    }

    /**
     * 拼接字符串列表，忽略 null
     */
    private String join(List<String> values, String separator) {
        if (values == null || values.isEmpty()) {
            return "";
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
        return builder.toString();
    }

    /**
     * 空安全的字符串列表
     */
    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    /**
     * 分数裁剪到 0-1
     */
    private double clamp(Double score) {
        if (score == null || score.isNaN()) {
            return 0;
        }
        return Math.min(1.0, Math.max(0.0, score));
    }

    /**
     * 空安全分数
     */
    private double safeScore(Double score) {
        return clamp(score);
    }

    /**
     * 保留三位小数
     */
    private double round(double score) {
        return Math.round(score * 1000.0) / 1000.0;
    }
}
