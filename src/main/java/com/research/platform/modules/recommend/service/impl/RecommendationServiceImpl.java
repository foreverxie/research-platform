package com.research.platform.modules.recommend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.research.platform.common.vo.RecommendationItemVO;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.mapper.DocumentMapper;
import com.research.platform.modules.document.service.DocumentAccessService;
import com.research.platform.modules.document.service.IDocumentService;
import com.research.platform.modules.recommend.service.IRecommendationService;
import com.research.platform.modules.recommend.vo.TopicVO;
import com.research.platform.utils.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 文献推荐服务实现
 *
 * <p>相似文献推荐复用文献模块的 {@link IDocumentService#recommendations(Long, int)}，
 * 保证详情页与推荐中心口径一致；主题推荐只做本地关键词聚合，不依赖 AI 服务。</p>
 */
@Service
public class RecommendationServiceImpl implements IRecommendationService {

    private static final int RECOMMEND_LIMIT = 8;

    private static final int TOP_TOPIC_COUNT = 8;

    private static final int RELATED_KEYWORD_COUNT = 5;

    private static final double TOPIC_BASE_SCORE = 0.55;

    private static final double TOPIC_STEP_SCORE = 0.08;

    private static final double TOPIC_MAX_SCORE = 0.99;

    private final DocumentMapper documentMapper;
    private final DocumentAccessService documentAccessService;
    private final IDocumentService documentService;

    public RecommendationServiceImpl(DocumentMapper documentMapper,
                                     DocumentAccessService documentAccessService,
                                     IDocumentService documentService) {
        this.documentMapper = documentMapper;
        this.documentAccessService = documentAccessService;
        this.documentService = documentService;
    }

    @Override
    public List<RecommendationItemVO> recommendations(Long documentId) {
        Long userId = CurrentUser.getCurrentUserId();

        Long targetDocumentId;
        if (documentId != null) {
            // 指定文献：必须校验归属
            documentAccessService.requireOwned(userId, documentId);
            targetDocumentId = documentId;
        } else {
            // 未指定：取最近更新的一篇文献作为推荐目标
            Document latest = documentMapper.selectOne(
                    new QueryWrapper<Document>()
                            .eq("user_id", userId)
                            .orderByDesc("updated_at")
                            .last("LIMIT 1")
            );
            if (latest == null) {
                return List.of();
            }
            targetDocumentId = latest.getId();
        }

        List<RecommendationItemVO> items = documentService.recommendations(targetDocumentId, RECOMMEND_LIMIT);
        return items == null ? List.of() : items;
    }

    @Override
    public List<TopicVO> topics() {
        Long userId = CurrentUser.getCurrentUserId();

        List<Document> documents = documentMapper.selectList(
                new QueryWrapper<Document>()
                        .select("id", "keywords")
                        .eq("user_id", userId)
        );

        if (documents.isEmpty()) {
            return List.of();
        }

        // 1. 关键词 → 文献列表
        Map<String, List<Long>> keywordDocuments = new LinkedHashMap<>();
        for (Document document : documents) {
            if (document.getKeywords() == null) {
                continue;
            }

            LinkedHashSet<String> documentKeywords = new LinkedHashSet<>();
            for (String keyword : document.getKeywords()) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                documentKeywords.add(keyword.trim());
            }

            for (String keyword : documentKeywords) {
                keywordDocuments
                        .computeIfAbsent(keyword, key -> new ArrayList<>())
                        .add(document.getId());
            }
        }

        if (keywordDocuments.isEmpty()) {
            return List.of();
        }

        // 2. 按涉及文献数降序，取前 8 个主题
        List<Map.Entry<String, List<Long>>> topEntries = keywordDocuments.entrySet().stream()
                .sorted(Map.Entry.<String, List<Long>>comparingByValue(
                                Comparator.comparingInt((List<Long> ids) -> ids.size()).reversed())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_TOPIC_COUNT)
                .toList();

        // 3. 为每个主题计算共现关键词
        Map<Long, Document> documentMap = new LinkedHashMap<>();
        for (Document document : documents) {
            documentMap.put(document.getId(), document);
        }

        List<TopicVO> topics = new ArrayList<>(topEntries.size());
        for (Map.Entry<String, List<Long>> entry : topEntries) {
            String topic = entry.getKey();
            List<Long> documentIds = entry.getValue();

            TopicVO vo = new TopicVO();
            vo.setTopic(topic);
            vo.setScore(Math.min(TOPIC_MAX_SCORE, round(TOPIC_BASE_SCORE + documentIds.size() * TOPIC_STEP_SCORE)));
            vo.setReason("你的 " + documentIds.size() + " 篇文献涉及该主题，可优先补充综述与最新工作");
            vo.setRelatedKeywords(relatedKeywords(topic, documentIds, documentMap));
            vo.setDocumentIds(documentIds);
            topics.add(vo);
        }
        return topics;
    }

    /**
     * 计算与主题共现的其它关键词（按共现次数取前 5）
     */
    private List<String> relatedKeywords(String topic,
                                         List<Long> documentIds,
                                         Map<Long, Document> documentMap) {
        Map<String, Integer> counter = new LinkedHashMap<>();

        for (Long documentId : documentIds) {
            Document document = documentMap.get(documentId);
            if (document == null || document.getKeywords() == null) {
                continue;
            }

            for (String keyword : document.getKeywords()) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                String name = keyword.trim();
                if (name.equals(topic)) {
                    continue;
                }
                counter.merge(name, 1, Integer::sum);
            }
        }

        return counter.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(RELATED_KEYWORD_COUNT)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 保留两位小数
     */
    private double round(double score) {
        return Math.round(score * 100.0) / 100.0;
    }
}
