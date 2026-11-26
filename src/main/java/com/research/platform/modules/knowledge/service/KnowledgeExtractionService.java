package com.research.platform.modules.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.research.platform.infrastructure.ai.AiCommands;
import com.research.platform.infrastructure.ai.AiResults;
import com.research.platform.infrastructure.ai.AiServiceClient;
import com.research.platform.modules.knowledge.entity.KnowledgeEntity;
import com.research.platform.modules.knowledge.entity.KnowledgeRelation;
import com.research.platform.modules.knowledge.mapper.KnowledgeEntityMapper;
import com.research.platform.modules.knowledge.mapper.KnowledgeRelationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 知识实体/关系抽取与落库。
 *
 * <p>AI 只返回名称和类型，本服务负责把名称映射为 MySQL 主键，
 * 并按唯一键幂等写入，避免与元数据建图重复。</p>
 */
@Service
public class KnowledgeExtractionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExtractionService.class);

    private static final Set<String> ENTITY_TYPES = Set.of(
            "PAPER", "AUTHOR", "TOPIC", "METHOD", "MODEL", "DATASET", "INSTITUTION"
    );

    private static final Set<String> RELATION_TYPES = Set.of(
            "AUTHORED", "STUDIES", "USES_METHOD", "USES_DATASET", "CITES", "AFFILIATED"
    );

    private static final Map<String, String> RELATION_ALIASES = Map.of(
            "USES_MODEL", "USES_METHOD",
            "USES_ALGORITHM", "USES_METHOD",
            "USES", "USES_METHOD",
            "BELONGS_TO", "AFFILIATED"
    );

    private final KnowledgeEntityMapper knowledgeEntityMapper;
    private final KnowledgeRelationMapper knowledgeRelationMapper;
    private final AiServiceClient aiServiceClient;

    public KnowledgeExtractionService(
            KnowledgeEntityMapper knowledgeEntityMapper,
            KnowledgeRelationMapper knowledgeRelationMapper,
            AiServiceClient aiServiceClient
    ) {
        this.knowledgeEntityMapper = knowledgeEntityMapper;
        this.knowledgeRelationMapper = knowledgeRelationMapper;
        this.aiServiceClient = aiServiceClient;
    }

    /**
     * 抽取知识并写入 MySQL；失败只记录日志，不影响解析任务。
     */
    public void extractAndSave(Long userId,
                               Long documentId,
                               Long parseRecordId,
                               String markdownPath,
                               String title) {
        if (userId == null || documentId == null) {
            return;
        }

        try {
            AiResults.Knowledge knowledge = aiServiceClient.extractKnowledge(
                    new AiCommands.Knowledge(documentId, parseRecordId, markdownPath, title));

            if (knowledge == null) {
                return;
            }

            Map<String, KnowledgeEntity> index = new HashMap<>();
            for (AiResults.KnowledgeEntity item : safe(knowledge.entities())) {
                KnowledgeEntity entity = upsertEntity(userId, documentId, item, index);
                if (entity != null) {
                    index.put(entityKey(entity.getName(), entity.getType()), entity);
                }
            }

            if (index.isEmpty()) {
                log.warn("知识抽取没有返回实体：documentId={}", documentId);
                return;
            }

            Set<String> relationKeys = new HashSet<>();
            for (AiResults.KnowledgeRelation item : safe(knowledge.relations())) {
                upsertRelation(userId, item, index, relationKeys);
            }

            log.info("知识抽取完成：documentId={}, entities={}, relations={}, model={}",
                    documentId, index.size(), relationKeys.size(), knowledge.modelName());
        } catch (Exception e) {
            log.warn("知识抽取失败，保留已有图谱：documentId={}, reason={}", documentId, e.getMessage());
        }
    }

    private KnowledgeEntity upsertEntity(Long userId,
                                         Long documentId,
                                         AiResults.KnowledgeEntity item,
                                         Map<String, KnowledgeEntity> index) {
        String name = normalizeName(item.name());
        String type = normalizeType(item.type());

        if (name == null || type == null || !ENTITY_TYPES.contains(type)) {
            return null;
        }

        String key = entityKey(name, type);
        KnowledgeEntity existing = index.get(key);
        if (existing == null) {
            existing = knowledgeEntityMapper.selectOne(new LambdaQueryWrapper<KnowledgeEntity>()
                    .eq(KnowledgeEntity::getUserId, userId)
                    .eq(KnowledgeEntity::getName, name)
                    .eq(KnowledgeEntity::getType, type)
                    .last("LIMIT 1"));
        }

        if (existing != null) {
            if ((existing.getDescription() == null || existing.getDescription().isBlank())
                    && item.description() != null && !item.description().isBlank()) {
                existing.setDescription(truncate(item.description(), 500));
                knowledgeEntityMapper.updateById(existing);
            }
            return existing;
        }

        KnowledgeEntity entity = new KnowledgeEntity();
        entity.setUserId(userId);
        entity.setDocumentId("PAPER".equals(type) || "METHOD".equals(type)
                || "MODEL".equals(type) || "DATASET".equals(type) ? documentId : null);
        entity.setName(name);
        entity.setType(type);
        entity.setWeight(weightOf(type));
        entity.setDescription(truncate(item.description(), 500));
        entity.setCreatedAt(LocalDateTime.now());

        try {
            knowledgeEntityMapper.insert(entity);
            return entity;
        } catch (DuplicateKeyException e) {
            KnowledgeEntity concurrent = knowledgeEntityMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeEntity>()
                            .eq(KnowledgeEntity::getUserId, userId)
                            .eq(KnowledgeEntity::getName, name)
                            .eq(KnowledgeEntity::getType, type)
                            .last("LIMIT 1"));
            return concurrent;
        }
    }

    private void upsertRelation(Long userId,
                                AiResults.KnowledgeRelation item,
                                Map<String, KnowledgeEntity> index,
                                Set<String> relationKeys) {
        String sourceName = normalizeName(item.sourceName());
        String targetName = normalizeName(item.targetName());
        String sourceType = normalizeType(item.sourceType());
        String targetType = normalizeType(item.targetType());
        String relationType = normalizeType(item.relationType());

        if (sourceName == null || targetName == null
                || sourceType == null || targetType == null || relationType == null) {
            return;
        }

        relationType = RELATION_ALIASES.getOrDefault(relationType, relationType);
        if (!RELATION_TYPES.contains(relationType)) {
            return;
        }

        KnowledgeEntity source = index.get(entityKey(sourceName, sourceType));
        KnowledgeEntity target = index.get(entityKey(targetName, targetType));

        if (source == null || target == null || source.getId() == null || target.getId() == null) {
            return;
        }
        if (source.getId().equals(target.getId())) {
            return;
        }

        String key = relationType + ":" + source.getId() + "->" + target.getId();
        if (!relationKeys.add(key)) {
            return;
        }

        double confidence = clamp(item.confidence());

        KnowledgeRelation existing = knowledgeRelationMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeRelation>()
                        .eq(KnowledgeRelation::getUserId, userId)
                        .eq(KnowledgeRelation::getSourceId, source.getId())
                        .eq(KnowledgeRelation::getTargetId, target.getId())
                        .eq(KnowledgeRelation::getRelationType, relationType)
                        .last("LIMIT 1"));

        if (existing != null) {
            if (confidence > (existing.getConfidence() == null ? 0 : existing.getConfidence())) {
                existing.setConfidence(confidence);
                if (item.evidence() != null && !item.evidence().isBlank()) {
                    existing.setEvidence(truncate(item.evidence(), 500));
                }
                knowledgeRelationMapper.updateById(existing);
            }
            return;
        }

        KnowledgeRelation relation = new KnowledgeRelation();
        relation.setUserId(userId);
        relation.setSourceId(source.getId());
        relation.setTargetId(target.getId());
        relation.setRelationType(relationType);
        relation.setConfidence(confidence);
        relation.setEvidence(truncate(item.evidence(), 500));
        relation.setCreatedAt(LocalDateTime.now());

        try {
            knowledgeRelationMapper.insert(relation);
        } catch (DuplicateKeyException e) {
            log.debug("知识关系已存在，跳过：{}", key);
        }
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim().replaceAll("\\s+", " ");
        return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
    }

    private String normalizeType(String type) {
        return type == null ? null : type.trim().toUpperCase(Locale.ROOT);
    }

    private String entityKey(String name, String type) {
        return (name == null ? "" : name.toLowerCase(Locale.ROOT)) + "|" + type;
    }

    private int weightOf(String type) {
        return switch (type) {
            case "PAPER" -> 8;
            case "AUTHOR", "TOPIC" -> 5;
            case "METHOD", "MODEL" -> 4;
            case "DATASET" -> 3;
            default -> 2;
        };
    }

    private double clamp(Double value) {
        if (value == null) {
            return 0.8;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
