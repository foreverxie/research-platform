package com.research.platform.modules.card.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.modules.card.dto.KnowledgeCardCreateDTO;
import com.research.platform.modules.card.dto.KnowledgeCardUpdateDTO;
import com.research.platform.modules.card.entity.KnowledgeCard;
import com.research.platform.modules.card.mapper.KnowledgeCardMapper;
import com.research.platform.modules.card.service.IKnowledgeCardService;
import com.research.platform.modules.card.vo.KnowledgeCardVO;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.service.DocumentAccessService;
import com.research.platform.utils.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
 * 知识卡片服务实现
 *
 * <p>卡片关联的文献 / 标签以 JSON 落库；读取时按 document_ids 实时补齐
 * documentTitles，保证文献改名后卡片展示同步更新。</p>
 */
@Service
public class KnowledgeCardServiceImpl extends ServiceImpl<KnowledgeCardMapper, KnowledgeCard>
        implements IKnowledgeCardService {

    private static final String DEFAULT_CARD_TYPE = "CONCEPT";

    private static final Set<String> CARD_TYPES = Set.of(
            "METHOD", "MODEL", "CONCEPT", "CONCLUSION"
    );

    private final KnowledgeCardMapper knowledgeCardMapper;
    private final DocumentAccessService documentAccessService;

    public KnowledgeCardServiceImpl(
            KnowledgeCardMapper knowledgeCardMapper,
            DocumentAccessService documentAccessService
    ) {
        this.knowledgeCardMapper = knowledgeCardMapper;
        this.documentAccessService = documentAccessService;
    }

    /**
     * 查询当前用户的知识卡片
     */
    @Override
    public List<KnowledgeCardVO> listCards() {
        Long userId = CurrentUser.getCurrentUserId();

        List<KnowledgeCard> cards = knowledgeCardMapper.selectList(
                new QueryWrapper<KnowledgeCard>()
                        .eq("user_id", userId)
                        .orderByDesc("updated_at")
                        .orderByDesc("id")
        );
        if (cards.isEmpty()) {
            return new ArrayList<>();
        }

        // 批量收集卡片关联的文献，一次补齐标题
        Set<Long> documentIds = new LinkedHashSet<>();
        for (KnowledgeCard card : cards) {
            if (card.getDocumentIds() != null) {
                for (Long documentId : card.getDocumentIds()) {
                    if (documentId != null) {
                        documentIds.add(documentId);
                    }
                }
            }
        }
        Map<Long, String> titleMap = resolveTitleMap(userId, documentIds);

        List<KnowledgeCardVO> result = new ArrayList<>(cards.size());
        for (KnowledgeCard card : cards) {
            result.add(toVO(card, titleMap));
        }
        return result;
    }

    /**
     * 新建知识卡片
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeCardVO create(KnowledgeCardCreateDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();

        // 1. 标题校验
        String title = dto.getTitle() == null ? "" : dto.getTitle().trim();
        if (title.isEmpty()) {
            throw new BusinessException(400, "卡片标题不能为空");
        }

        // 2. 关联文献归属校验
        List<Long> documentIds = normalizeDocumentIds(dto.getDocumentIds());
        validateDocuments(userId, documentIds);

        // 3. 落库
        KnowledgeCard card = new KnowledgeCard();
        card.setUserId(userId);
        card.setTitle(title);
        card.setContent(dto.getContent() == null ? "" : dto.getContent());
        card.setCardType(resolveCardType(dto.getCardType()));
        card.setDocumentIds(documentIds);
        card.setTagNames(normalizeTagNames(dto.getTagNames()));
        knowledgeCardMapper.insert(card);

        KnowledgeCard saved = knowledgeCardMapper.selectById(card.getId());
        return toVO(saved, resolveTitleMap(userId, documentIds));
    }

    /**
     * 修改知识卡片
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeCardVO update(Long id, KnowledgeCardUpdateDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();

        // 1. 归属校验
        KnowledgeCard card = getOwnedCard(userId, id);

        // 2. 关联文献修改时校验归属
        if (dto.getDocumentIds() != null) {
            List<Long> documentIds = normalizeDocumentIds(dto.getDocumentIds());
            validateDocuments(userId, documentIds);
            card.setDocumentIds(documentIds);
        }

        // 3. 其余字段按需修改
        if (dto.getTitle() != null) {
            String title = dto.getTitle().trim();
            if (title.isEmpty()) {
                throw new BusinessException(400, "卡片标题不能为空");
            }
            card.setTitle(title);
        }
        if (dto.getContent() != null) {
            card.setContent(dto.getContent());
        }
        if (dto.getCardType() != null) {
            card.setCardType(resolveCardType(dto.getCardType()));
        }
        if (dto.getTagNames() != null) {
            card.setTagNames(normalizeTagNames(dto.getTagNames()));
        }
        // 显式刷新更新时间，避免实体携带旧值导致数据库 ON UPDATE 不生效
        card.setUpdatedAt(LocalDateTime.now());

        knowledgeCardMapper.updateById(card);

        // 4. 返回最新数据
        KnowledgeCard saved = knowledgeCardMapper.selectById(card.getId());
        return toVO(saved, resolveTitleMap(userId, saved.getDocumentIds()));
    }

    /**
     * 删除知识卡片（逻辑删除）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Long userId = CurrentUser.getCurrentUserId();
        KnowledgeCard card = getOwnedCard(userId, id);
        // @TableLogic 生成的删除语句自动更新 deleted = 1
        knowledgeCardMapper.deleteById(card.getId());
    }

    /**
     * 查询当前用户拥有的知识卡片，找不到按 404 处理
     */
    private KnowledgeCard getOwnedCard(Long userId, Long id) {
        KnowledgeCard card = knowledgeCardMapper.selectOne(
                new QueryWrapper<KnowledgeCard>()
                        .eq("id", id)
                        .eq("user_id", userId)
        );
        if (card == null) {
            throw new BusinessException(404, "知识卡片不存在");
        }
        return card;
    }

    /**
     * 校验关联文献均属于当前用户，任一越权按 403 处理
     */
    private void validateDocuments(Long userId, List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        List<Document> owned = documentAccessService.listOwned(userId, documentIds);
        if (owned == null || owned.size() != documentIds.size()) {
            throw new BusinessException(403, "关联的文献不存在或无权访问");
        }
    }

    /**
     * 去重、过滤空值，保留请求顺序
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
     * 标签名称去重、去空白
     */
    private List<String> normalizeTagNames(List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> uniqueNames = new LinkedHashSet<>();
        for (String tagName : tagNames) {
            if (tagName == null) {
                continue;
            }
            String trimmed = tagName.trim();
            if (!trimmed.isEmpty()) {
                uniqueNames.add(trimmed);
            }
        }
        return new ArrayList<>(uniqueNames);
    }

    /**
     * 卡片类型校验与规范化
     */
    private String resolveCardType(String cardType) {
        if (cardType == null || cardType.trim().isEmpty()) {
            return DEFAULT_CARD_TYPE;
        }
        String normalized = cardType.trim().toUpperCase(Locale.ROOT);
        if (!CARD_TYPES.contains(normalized)) {
            throw new BusinessException(400, "无效的卡片类型");
        }
        return normalized;
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
     * Entity 转 VO，documentTitles 与 documentIds 保持一一对应
     */
    private KnowledgeCardVO toVO(KnowledgeCard card, Map<Long, String> titleMap) {
        if (card == null) {
            return null;
        }
        List<Long> documentIds = card.getDocumentIds() == null
                ? new ArrayList<>()
                : card.getDocumentIds();

        List<String> documentTitles = new ArrayList<>(documentIds.size());
        for (Long documentId : documentIds) {
            String title = titleMap == null ? null : titleMap.get(documentId);
            documentTitles.add(title == null ? "" : title);
        }

        KnowledgeCardVO vo = new KnowledgeCardVO();
        vo.setId(card.getId());
        vo.setUserId(card.getUserId());
        vo.setTitle(card.getTitle());
        vo.setContent(card.getContent());
        vo.setCardType(card.getCardType());
        vo.setDocumentIds(documentIds);
        vo.setDocumentTitles(documentTitles);
        vo.setTagNames(card.getTagNames() == null ? new ArrayList<>() : card.getTagNames());
        vo.setCreatedAt(card.getCreatedAt());
        vo.setUpdatedAt(card.getUpdatedAt());
        return vo;
    }
}
