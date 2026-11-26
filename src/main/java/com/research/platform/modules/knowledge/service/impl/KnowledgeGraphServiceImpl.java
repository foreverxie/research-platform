package com.research.platform.modules.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.modules.category.entity.Category;
import com.research.platform.modules.category.mapper.CategoryMapper;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.mapper.DocumentMapper;
import com.research.platform.modules.knowledge.entity.KnowledgeEntity;
import com.research.platform.modules.knowledge.entity.KnowledgeRelation;
import com.research.platform.modules.knowledge.mapper.KnowledgeEntityMapper;
import com.research.platform.modules.knowledge.mapper.KnowledgeRelationMapper;
import com.research.platform.modules.knowledge.service.IKnowledgeGraphService;
import com.research.platform.modules.knowledge.vo.EntityDetailVO;
import com.research.platform.modules.knowledge.vo.EntityNeighborVO;
import com.research.platform.modules.knowledge.vo.EntityVO;
import com.research.platform.modules.knowledge.vo.KnowledgeGraphVO;
import com.research.platform.modules.knowledge.vo.KnowledgeStatsVO;
import com.research.platform.modules.knowledge.vo.RelationVO;
import com.research.platform.modules.knowledge.vo.TypeCountVO;
import com.research.platform.modules.search.vo.SearchDocumentVO;
import com.research.platform.utils.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

/**
 * 知识图谱服务实现
 *
 * <p>图谱数据由两部分组成：
 * 1）元数据事实——文献、作者、关键词构成的 PAPER/AUTHOR/TOPIC 节点与 AUTHORED/STUDIES 关系，
 *    在查询前幂等补齐，保证 FastAPI 未部署时也有可用的知识图谱；
 * 2）AI 推断——由 FastAPI 写入的其它实体与关系，置信度低于 1.0，前端按"需人工核对"展示。</p>
 */
@Service
public class KnowledgeGraphServiceImpl implements IKnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphServiceImpl.class);

    private static final String TYPE_PAPER = "PAPER";
    private static final String TYPE_AUTHOR = "AUTHOR";
    private static final String TYPE_TOPIC = "TOPIC";

    private static final String RELATION_AUTHORED = "AUTHORED";
    private static final String RELATION_STUDIES = "STUDIES";

    private static final int PAPER_WEIGHT = 10;
    private static final int AUTHOR_WEIGHT = 5;
    private static final int TOPIC_WEIGHT = 3;

    /**
     * 建图时最多遍历的文献数
     */
    private static final int MAX_DOCUMENTS = 200;

    /**
     * knowledge_entity.name / knowledge_relation.evidence 的字段长度
     */
    private static final int NAME_MAX_LENGTH = 255;
    private static final int DESCRIPTION_MAX_LENGTH = 500;

    private static final double CONFIDENCE_METADATA = 1.0;
    private static final String EVIDENCE_AUTHOR = "文献作者列表";
    private static final String EVIDENCE_KEYWORD = "文献关键词";
    private static final String AUTHOR_DESCRIPTION = "由文献作者列表抽取的作者";
    private static final String TOPIC_DESCRIPTION = "由文献关键词抽取的研究主题";

    private final DocumentMapper documentMapper;
    private final KnowledgeEntityMapper knowledgeEntityMapper;
    private final KnowledgeRelationMapper knowledgeRelationMapper;
    private final CategoryMapper categoryMapper;

    public KnowledgeGraphServiceImpl(DocumentMapper documentMapper,
                                     KnowledgeEntityMapper knowledgeEntityMapper,
                                     KnowledgeRelationMapper knowledgeRelationMapper,
                                     CategoryMapper categoryMapper) {
        this.documentMapper = documentMapper;
        this.knowledgeEntityMapper = knowledgeEntityMapper;
        this.knowledgeRelationMapper = knowledgeRelationMapper;
        this.categoryMapper = categoryMapper;
    }

    @Override
    public KnowledgeGraphVO graph(String topic, List<String> types) {
        Long userId = CurrentUser.getCurrentUserId();

        // 元数据图谱幂等补齐。
        // 注意：AI 知识抽取在解析完成时也会写入 PAPER 实体（离线模式只写这一个实体），
        // 若用"最近实体时间 vs 文献更新时间"判断是否同步，会把作者/主题边永久跳过，
        // 导致图谱只有孤立节点、0 条关系。这里改为每次查询前做一次幂等补齐，
        // 建图规模有 MAX_DOCUMENTS 上限，代价可控。
        ensureMetadataGraph(userId);

        List<KnowledgeEntity> allEntities = knowledgeEntityMapper.selectList(
                new QueryWrapper<KnowledgeEntity>()
                        .eq("user_id", userId)
                        .orderByDesc("weight")
                        .orderByAsc("id")
        );

        List<KnowledgeRelation> allRelations = knowledgeRelationMapper.selectList(
                new QueryWrapper<KnowledgeRelation>()
                        .eq("user_id", userId)
                        .orderByAsc("id")
        );

        // 1. 实体类型过滤
        Set<String> typeFilter = normalizeTypes(types);
        List<KnowledgeEntity> entities = allEntities;
        if (!typeFilter.isEmpty()) {
            entities = entities.stream()
                    .filter(entity -> typeFilter.contains(entity.getType()))
                    .toList();
        }

        // 2. 主题过滤：名称/描述命中的实体 + 一跳邻居
        String topicKeyword = topic == null ? "" : topic.trim();
        if (!topicKeyword.isEmpty()) {
            Set<Long> matchedIds = new HashSet<>();
            for (KnowledgeEntity entity : allEntities) {
                if (containsIgnoreCase(entity.getName(), topicKeyword)
                        || containsIgnoreCase(entity.getDescription(), topicKeyword)) {
                    matchedIds.add(entity.getId());
                }
            }

            Set<Long> expandedIds = new HashSet<>(matchedIds);
            for (KnowledgeRelation relation : allRelations) {
                if (matchedIds.contains(relation.getSourceId())) {
                    expandedIds.add(relation.getTargetId());
                }
                if (matchedIds.contains(relation.getTargetId())) {
                    expandedIds.add(relation.getSourceId());
                }
            }

            entities = entities.stream()
                    .filter(entity -> expandedIds.contains(entity.getId()))
                    .toList();
        }

        // 3. 关系只保留两端都在结果集中的
        Set<Long> entityIds = new HashSet<>();
        for (KnowledgeEntity entity : entities) {
            entityIds.add(entity.getId());
        }

        List<KnowledgeRelation> relations = allRelations.stream()
                .filter(relation -> entityIds.contains(relation.getSourceId())
                        && entityIds.contains(relation.getTargetId()))
                .toList();

        // 4. 组装视图与统计
        KnowledgeGraphVO graph = new KnowledgeGraphVO();
        graph.setEntities(entities.stream().map(this::toEntityVO).toList());
        graph.setRelations(relations.stream().map(this::toRelationVO).toList());

        KnowledgeStatsVO stats = new KnowledgeStatsVO();
        stats.setEntityCount(entities.size());
        stats.setRelationCount(relations.size());
        stats.setByType(countByType(entities));
        graph.setStats(stats);
        return graph;
    }

    @Override
    public EntityDetailVO entityDetail(Long entityId) {
        Long userId = CurrentUser.getCurrentUserId();

        if (entityId == null) {
            throw new BusinessException(400, "实体ID不能为空");
        }

        KnowledgeEntity entity = knowledgeEntityMapper.selectById(entityId);
        if (entity == null || !Objects.equals(entity.getUserId(), userId)) {
            throw new BusinessException(404, "知识实体不存在或无权访问");
        }

        List<KnowledgeRelation> relations = knowledgeRelationMapper.selectList(
                new QueryWrapper<KnowledgeRelation>()
                        .eq("user_id", userId)
                        .and(condition -> condition
                                .eq("source_id", entityId)
                                .or()
                                .eq("target_id", entityId))
                        .orderByAsc("id")
        );

        // 一跳邻居实体
        Set<Long> neighborIds = new LinkedHashSet<>();
        for (KnowledgeRelation relation : relations) {
            Long otherId = entityId.equals(relation.getSourceId())
                    ? relation.getTargetId()
                    : relation.getSourceId();
            if (otherId != null) {
                neighborIds.add(otherId);
            }
        }

        Map<Long, KnowledgeEntity> neighborEntities = new HashMap<>();
        if (!neighborIds.isEmpty()) {
            for (KnowledgeEntity neighbor : knowledgeEntityMapper.selectBatchIds(neighborIds)) {
                neighborEntities.put(neighbor.getId(), neighbor);
            }
        }

        List<EntityNeighborVO> neighbors = new ArrayList<>();
        Set<Long> documentIds = new LinkedHashSet<>();
        if (entity.getDocumentId() != null) {
            documentIds.add(entity.getDocumentId());
        }

        for (KnowledgeRelation relation : relations) {
            Long otherId = entityId.equals(relation.getSourceId())
                    ? relation.getTargetId()
                    : relation.getSourceId();
            KnowledgeEntity neighbor = otherId == null ? null : neighborEntities.get(otherId);
            if (neighbor == null) {
                continue;
            }

            if (neighbor.getDocumentId() != null) {
                documentIds.add(neighbor.getDocumentId());
            }

            EntityNeighborVO neighborVO = new EntityNeighborVO();
            neighborVO.setEntity(toEntityVO(neighbor));
            neighborVO.setRelation(toRelationVO(relation));
            neighbors.add(neighborVO);
        }

        EntityDetailVO detail = new EntityDetailVO();
        detail.setEntity(toEntityVO(entity));
        detail.setNeighbors(neighbors);
        detail.setDocuments(loadDocuments(userId, documentIds));
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ensureMetadataGraph(Long userId) {
        List<Document> documents = documentMapper.selectList(
                new QueryWrapper<Document>()
                        .eq("user_id", userId)
                        .orderByDesc("updated_at")
                        .last("LIMIT " + MAX_DOCUMENTS)
        );

        if (documents.isEmpty()) {
            return;
        }

        // 预载已有实体与关系，避免逐条查询；唯一键已保证 (user_id, name, type) 与关系唯一
        Map<String, KnowledgeEntity> entityIndex = new HashMap<>();
        for (KnowledgeEntity entity : knowledgeEntityMapper.selectList(
                new QueryWrapper<KnowledgeEntity>().eq("user_id", userId))) {
            entityIndex.put(entityKey(entity.getName(), entity.getType()), entity);
        }

        Set<String> relationKeys = new HashSet<>();
        for (KnowledgeRelation relation : knowledgeRelationMapper.selectList(
                new QueryWrapper<KnowledgeRelation>().eq("user_id", userId))) {
            relationKeys.add(relationKey(relation.getSourceId(), relation.getTargetId(), relation.getRelationType()));
        }

        for (Document document : documents) {
            String paperName = normalizeName(document.getTitle());
            if (paperName == null) {
                continue;
            }

            KnowledgeEntity paper = upsertEntity(
                    entityIndex, userId, paperName, TYPE_PAPER, document.getId(),
                    PAPER_WEIGHT, paperDescription(document));

            // 作者 → 论文：AUTHORED
            for (String authorName : normalizeNames(document.getAuthors())) {
                KnowledgeEntity author = upsertEntity(
                        entityIndex, userId, authorName, TYPE_AUTHOR, null,
                        AUTHOR_WEIGHT, AUTHOR_DESCRIPTION);
                upsertRelation(relationKeys, userId, author.getId(), paper.getId(),
                        RELATION_AUTHORED, CONFIDENCE_METADATA, EVIDENCE_AUTHOR);
            }

            // 论文 → 主题：STUDIES
            for (String keyword : normalizeNames(document.getKeywords())) {
                KnowledgeEntity topicEntity = upsertEntity(
                        entityIndex, userId, keyword, TYPE_TOPIC, null,
                        TOPIC_WEIGHT, TOPIC_DESCRIPTION);
                upsertRelation(relationKeys, userId, paper.getId(), topicEntity.getId(),
                        RELATION_STUDIES, CONFIDENCE_METADATA, EVIDENCE_KEYWORD);
            }
        }
    }

    /**
     * 按 (user_id, name, type) 幂等写入实体
     */
    private KnowledgeEntity upsertEntity(Map<String, KnowledgeEntity> entityIndex,
                                         Long userId,
                                         String name,
                                         String type,
                                         Long documentId,
                                         int weight,
                                         String description) {
        String key = entityKey(name, type);
        KnowledgeEntity existing = entityIndex.get(key);
        if (existing != null) {
            return existing;
        }

        KnowledgeEntity entity = new KnowledgeEntity();
        entity.setUserId(userId);
        entity.setDocumentId(documentId);
        entity.setName(name);
        entity.setType(type);
        entity.setWeight(weight);
        entity.setDescription(truncate(description, DESCRIPTION_MAX_LENGTH));
        entity.setCreatedAt(LocalDateTime.now());

        try {
            knowledgeEntityMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发建图时唯一键冲突：回查已存在的实体
            KnowledgeEntity concurrent = knowledgeEntityMapper.selectOne(
                    new QueryWrapper<KnowledgeEntity>()
                            .eq("user_id", userId)
                            .eq("name", name)
                            .eq("type", type)
            );
            if (concurrent == null) {
                throw e;
            }
            entityIndex.put(key, concurrent);
            return concurrent;
        }

        entityIndex.put(key, entity);
        return entity;
    }

    /**
     * 按关系唯一键幂等写入关系
     */
    private void upsertRelation(Set<String> relationKeys,
                                Long userId,
                                Long sourceId,
                                Long targetId,
                                String relationType,
                                double confidence,
                                String evidence) {
        if (sourceId == null || targetId == null) {
            return;
        }

        String key = relationKey(sourceId, targetId, relationType);
        if (!relationKeys.add(key)) {
            return;
        }

        KnowledgeRelation relation = new KnowledgeRelation();
        relation.setUserId(userId);
        relation.setSourceId(sourceId);
        relation.setTargetId(targetId);
        relation.setRelationType(relationType);
        relation.setConfidence(confidence);
        relation.setEvidence(evidence);
        relation.setCreatedAt(LocalDateTime.now());

        try {
            knowledgeRelationMapper.insert(relation);
        } catch (DuplicateKeyException e) {
            // 并发建图时忽略重复关系
            log.debug("知识关系已存在，跳过：{}", key);
        }
    }

    /**
     * 实体键：名称 + 类型（与 DDL 唯一键一致）
     */
    private String entityKey(String name, String type) {
        return (name == null ? "" : name.toLowerCase(Locale.ROOT)) + "|" + type;
    }

    /**
     * 关系键：源 + 目标 + 类型（与 DDL 唯一键一致）
     */
    private String relationKey(Long sourceId, Long targetId, String relationType) {
        return sourceId + "->" + targetId + ":" + relationType;
    }

    /**
     * 论文实体描述：期刊、年份、作者
     */
    private String paperDescription(Document document) {
        List<String> parts = new ArrayList<>();
        if (document.getJournal() != null && !document.getJournal().isBlank()) {
            parts.add(document.getJournal().trim());
        }
        if (document.getPublicationYear() != null) {
            parts.add(String.valueOf(document.getPublicationYear()));
        }

        String authors = joinNames(document.getAuthors(), "、");
        if (!authors.isEmpty()) {
            parts.add(authors);
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    /**
     * 规范化实体名称：去空白、压缩空格、按字段长度截断
     */
    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        String normalized = name.trim().replaceAll("\\s+", " ");
        return truncate(normalized, NAME_MAX_LENGTH);
    }

    /**
     * 规范化名称列表
     */
    private List<String> normalizeNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (String name : names) {
            String normalized = normalizeName(name);
            if (normalized != null && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    /**
     * 加载关联文献（带分类名称）
     */
    private List<SearchDocumentVO> loadDocuments(Long userId, Set<Long> documentIds) {
        if (documentIds.isEmpty()) {
            return List.of();
        }

        List<Document> documents = documentMapper.selectList(
                new QueryWrapper<Document>()
                        .eq("user_id", userId)
                        .in("id", documentIds)
                        .orderByDesc("publication_year")
                        .orderByDesc("id")
        );

        if (documents.isEmpty()) {
            return List.of();
        }

        Set<Long> categoryIds = new LinkedHashSet<>();
        for (Document document : documents) {
            if (document.getCategoryId() != null) {
                categoryIds.add(document.getCategoryId());
            }
        }

        Map<Long, String> categoryNames = new HashMap<>();
        if (!categoryIds.isEmpty()) {
            for (Category category : categoryMapper.selectBatchIds(categoryIds)) {
                categoryNames.put(category.getId(), category.getName());
            }
        }

        List<SearchDocumentVO> result = new ArrayList<>(documents.size());
        for (Document document : documents) {
            SearchDocumentVO vo = new SearchDocumentVO();
            vo.setId(document.getId());
            vo.setTitle(document.getTitle());
            vo.setAuthors(document.getAuthors() == null ? List.of() : document.getAuthors());
            vo.setAbstractText(document.getAbstractText());
            vo.setKeywords(document.getKeywords() == null ? List.of() : document.getKeywords());
            vo.setPublicationYear(document.getPublicationYear());
            vo.setJournal(document.getJournal());
            vo.setDocumentType(document.getDocumentType());
            vo.setCategoryId(document.getCategoryId());
            vo.setCategoryName(document.getCategoryId() == null
                    ? null
                    : categoryNames.get(document.getCategoryId()));
            vo.setParseStatus(document.getParseStatus());
            vo.setCurrentParseRecordId(document.getCurrentParseRecordId());
            result.add(vo);
        }
        return result;
    }

    /**
     * 按实体类型统计数量（数量降序）
     */
    private List<TypeCountVO> countByType(List<KnowledgeEntity> entities) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (KnowledgeEntity entity : entities) {
            String type = entity.getType() == null ? "UNKNOWN" : entity.getType();
            counter.merge(type, 1, Integer::sum);
        }

        return counter.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new TypeCountVO(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 解析实体类型过滤参数
     */
    private Set<String> normalizeTypes(List<String> types) {
        if (types == null || types.isEmpty()) {
            return Set.of();
        }

        Set<String> result = new HashSet<>();
        for (String type : types) {
            if (type == null || type.isBlank()) {
                continue;
            }
            for (String item : type.split(",")) {
                if (!item.isBlank()) {
                    result.add(item.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return result;
    }

    /**
     * 忽略大小写的包含判断
     */
    private boolean containsIgnoreCase(String text, String keyword) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isEmpty()) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    /**
     * 拼接名称列表
     */
    private String joinNames(List<String> names, String separator) {
        if (names == null || names.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(separator);
            }
            builder.append(name.trim());
        }
        return builder.toString();
    }

    /**
     * 按字符截断
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    /**
     * 实体转视图
     */
    private EntityVO toEntityVO(KnowledgeEntity entity) {
        EntityVO vo = new EntityVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setType(entity.getType());
        vo.setDocumentId(entity.getDocumentId());
        vo.setWeight(entity.getWeight());
        vo.setDescription(entity.getDescription());
        return vo;
    }

    /**
     * 关系转视图
     */
    private RelationVO toRelationVO(KnowledgeRelation relation) {
        RelationVO vo = new RelationVO();
        vo.setId(relation.getId());
        vo.setSourceId(relation.getSourceId());
        vo.setTargetId(relation.getTargetId());
        vo.setRelationType(relation.getRelationType());
        vo.setConfidence(relation.getConfidence());
        vo.setEvidence(relation.getEvidence());
        return vo;
    }
}
