package com.research.platform.modules.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.research.platform.common.enums.ChatRole;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.infrastructure.ai.AiCommands;
import com.research.platform.infrastructure.ai.AiResults;
import com.research.platform.infrastructure.ai.AiServiceClient;
import com.research.platform.modules.chat.convert.ChatConvert;
import com.research.platform.modules.chat.dto.ChatSendDTO;
import com.research.platform.modules.chat.dto.ConversationCreateDTO;
import com.research.platform.modules.chat.entity.ChatMessage;
import com.research.platform.modules.chat.entity.ChatReference;
import com.research.platform.modules.chat.entity.Conversation;
import com.research.platform.modules.chat.mapper.ChatMessageMapper;
import com.research.platform.modules.chat.mapper.ChatReferenceMapper;
import com.research.platform.modules.chat.mapper.ConversationMapper;
import com.research.platform.modules.chat.service.IChatService;
import com.research.platform.modules.chat.vo.ChatMessageVO;
import com.research.platform.modules.chat.vo.ChatReferenceVO;
import com.research.platform.modules.chat.vo.ConversationVO;
import com.research.platform.modules.document.entity.Document;
import com.research.platform.modules.document.entity.DocumentChunk;
import com.research.platform.modules.document.mapper.DocumentChunkMapper;
import com.research.platform.modules.document.service.DocumentAccessService;
import com.research.platform.utils.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 科研问答服务实现
 *
 * <p>会话按用户隔离；AI 服务不可用时保存一条兜底的 ASSISTANT 消息，
 * 保证会话历史完整，而不是直接向调用方抛出 503。</p>
 */
@Service
public class ChatServiceImpl implements IChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    /**
     * 新建会话的默认标题
     */
    private static final String DEFAULT_TITLE = "新的问答会话";

    /**
     * AI 不可用时写入的兜底回答
     */
    private static final String AI_UNAVAILABLE_MESSAGE =
            "抱歉，AI 服务当前不可用（未部署或已关闭），暂时无法基于文献库生成回答。"
                    + "你仍可以使用关键词检索、文献管理与笔记功能；AI 服务恢复后请重新提问。";

    /**
     * 会话标题取问题前 24 字
     */
    private static final int TITLE_MAX_LENGTH = 24;

    /**
     * RAG 检索的文本块数量
     */
    private static final int TOP_K = 6;

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatReferenceMapper chatReferenceMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentAccessService documentAccessService;
    private final AiServiceClient aiServiceClient;

    public ChatServiceImpl(ConversationMapper conversationMapper,
                           ChatMessageMapper chatMessageMapper,
                           ChatReferenceMapper chatReferenceMapper,
                           DocumentChunkMapper documentChunkMapper,
                           DocumentAccessService documentAccessService,
                           AiServiceClient aiServiceClient) {
        this.conversationMapper = conversationMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.chatReferenceMapper = chatReferenceMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.documentAccessService = documentAccessService;
        this.aiServiceClient = aiServiceClient;
    }

    @Override
    public List<ConversationVO> listConversations() {
        Long userId = CurrentUser.getCurrentUserId();

        List<Conversation> conversations = conversationMapper.selectList(
                new QueryWrapper<Conversation>()
                        .eq("user_id", userId)
                        .orderByDesc("updated_at")
                        .orderByDesc("id")
        );

        if (conversations.isEmpty()) {
            return List.of();
        }

        List<Long> conversationIds = conversations.stream().map(Conversation::getId).toList();
        Map<Long, Long> messageCounts = countMessages(conversationIds);

        return conversations.stream()
                .map(conversation -> ChatConvert.toVO(
                        conversation,
                        messageCounts.getOrDefault(conversation.getId(), 0L)))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationVO createConversation(ConversationCreateDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();

        String title = dto == null ? null : dto.getTitle();
        title = (title == null || title.isBlank()) ? DEFAULT_TITLE : title.trim();
        if (title.length() > 100) {
            title = title.substring(0, 100);
        }

        LocalDateTime now = LocalDateTime.now();
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setTitle(title);
        conversation.setDeleted(0);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);

        Conversation saved = conversationMapper.selectById(conversation.getId());
        return ChatConvert.toVO(saved, 0L);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(Long conversationId) {
        Long userId = CurrentUser.getCurrentUserId();

        Conversation conversation = requireOwned(userId, conversationId);
        // 逻辑删除：@TableLogic 会把 deleted 置为 1，消息与引用保留
        conversationMapper.deleteById(conversation.getId());
    }

    @Override
    public List<ChatMessageVO> listMessages(Long conversationId) {
        Long userId = CurrentUser.getCurrentUserId();

        Conversation conversation = requireOwned(userId, conversationId);

        List<ChatMessage> messages = chatMessageMapper.selectList(
                new QueryWrapper<ChatMessage>()
                        .eq("conversation_id", conversation.getId())
                        .orderByAsc("id")
        );

        return toMessageVOs(userId, messages);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> sendMessage(ChatSendDTO dto) {
        Long userId = CurrentUser.getCurrentUserId();
        String question = dto.getContent().trim();

        // 1. 解析会话：无会话或会话不属于当前用户时新建
        Conversation conversation = resolveConversation(userId, dto.getConversationId(), question);

        // 2. 保存用户消息
        ChatMessage userMessage = new ChatMessage();
        userMessage.setConversationId(conversation.getId());
        userMessage.setRole(ChatRole.USER.name());
        userMessage.setContent(question);
        userMessage.setCreatedAt(LocalDateTime.now());
        chatMessageMapper.insert(userMessage);

        // 3. 文献范围校验：只保留当前用户拥有的文献，并收集当前有效解析版本
        List<Document> scopedDocuments;
        List<Long> scopedDocumentIds = null;
        if (dto.getDocumentIds() != null && !dto.getDocumentIds().isEmpty()) {
            List<Long> requestedIds = dto.getDocumentIds().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            scopedDocuments = documentAccessService.listOwned(userId, requestedIds);
            scopedDocumentIds = scopedDocuments.stream()
                    .map(Document::getId)
                    .toList();
        } else {
            scopedDocuments = documentAccessService.listOwned(userId);
        }

        List<Long> parseRecordIds = scopedDocuments.stream()
                .map(Document::getCurrentParseRecordId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // 限定了文献范围但范围内没有可检索版本时，用不存在的版本号避免退化成全库检索
        if (scopedDocumentIds != null && parseRecordIds.isEmpty()) {
            parseRecordIds = List.of(-1L);
        }

        // 4. 调用 AI 服务；失败时降级为兜底回答，不中断会话
        AiResults.Answer answer = null;
        try {
            answer = aiServiceClient.ask(new AiCommands.Ask(
                    userId, question, scopedDocumentIds, parseRecordIds, TOP_K));
        } catch (Exception e) {
            log.warn("科研问答调用 AI 服务失败，写入兜底回答：{}", e.getMessage());
        }

        // 5. 保存助手消息（成功回答或兜底回答）
        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setConversationId(conversation.getId());
        assistantMessage.setRole(ChatRole.ASSISTANT.name());
        assistantMessage.setCreatedAt(LocalDateTime.now());

        List<ChatReference> references = new ArrayList<>();
        if (answer != null && answer.answer() != null && !answer.answer().isBlank()) {
            assistantMessage.setContent(answer.answer());
            assistantMessage.setModelName(answer.modelName());
            assistantMessage.setTokenUsage(tokenUsageOf(answer.promptTokens(), answer.completionTokens()));
            references = buildReferences(userId, answer.references());
        } else {
            assistantMessage.setContent(AI_UNAVAILABLE_MESSAGE);
            assistantMessage.setModelName("system");
        }
        chatMessageMapper.insert(assistantMessage);

        // 6. 保存引用
        for (ChatReference reference : references) {
            reference.setMessageId(assistantMessage.getId());
            chatReferenceMapper.insert(reference);
        }

        // 7. 更新会话时间；仍为默认标题时改为问题前 24 字
        conversation.setUpdatedAt(LocalDateTime.now());
        if (DEFAULT_TITLE.equals(conversation.getTitle())) {
            conversation.setTitle(truncate(question, TITLE_MAX_LENGTH));
        }
        conversationMapper.updateById(conversation);

        // 8. 返回最新落库的两条消息
        ChatMessage savedUserMessage = chatMessageMapper.selectById(userMessage.getId());
        ChatMessage savedAssistantMessage = chatMessageMapper.selectById(assistantMessage.getId());
        if (savedUserMessage == null) {
            savedUserMessage = userMessage;
        }
        if (savedAssistantMessage == null) {
            savedAssistantMessage = assistantMessage;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conversation.getId());
        result.put("userMessage", toMessageVOs(userId, List.of(savedUserMessage)).get(0));
        result.put("assistantMessage", toMessageVOs(userId, List.of(savedAssistantMessage)).get(0));
        return result;
    }

    /**
     * 解析目标会话：会话不存在或不属于当前用户时新建会话
     */
    private Conversation resolveConversation(Long userId, Long conversationId, String question) {
        if (conversationId != null) {
            Conversation existing = conversationMapper.selectOne(
                    new QueryWrapper<Conversation>()
                            .eq("id", conversationId)
                            .eq("user_id", userId)
            );
            if (existing != null) {
                return existing;
            }
        }

        LocalDateTime now = LocalDateTime.now();
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setTitle(truncate(question, TITLE_MAX_LENGTH));
        conversation.setDeleted(0);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        return conversation;
    }

    /**
     * 校验会话归属
     */
    private Conversation requireOwned(Long userId, Long conversationId) {
        if (conversationId == null) {
            throw new BusinessException(400, "会话ID不能为空");
        }

        Conversation conversation = conversationMapper.selectOne(
                new QueryWrapper<Conversation>()
                        .eq("id", conversationId)
                        .eq("user_id", userId)
        );

        if (conversation == null) {
            throw new BusinessException(404, "会话不存在或无权访问");
        }
        return conversation;
    }

    /**
     * 统计每个会话的消息条数（一次分组查询）
     */
    private Map<Long, Long> countMessages(List<Long> conversationIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (conversationIds == null || conversationIds.isEmpty()) {
            return counts;
        }

        List<Map<String, Object>> rows = chatMessageMapper.selectMaps(
                new QueryWrapper<ChatMessage>()
                        .select("conversation_id AS cid", "COUNT(*) AS cnt")
                        .in("conversation_id", conversationIds)
                        .groupBy("conversation_id")
        );

        for (Map<String, Object> row : rows) {
            Object conversationId = ignoreCaseValue(row, "cid");
            Object count = ignoreCaseValue(row, "cnt");
            if (conversationId instanceof Number cid && count instanceof Number cnt) {
                counts.put(cid.longValue(), cnt.longValue());
            }
        }
        return counts;
    }

    /**
     * 从结果 Map 中按忽略大小写取列值（不同 MySQL 版本返回的列名大小写不同）
     */
    private Object ignoreCaseValue(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 批量把消息实体转换为视图，并补齐引用、文献标题与原文片段
     */
    private List<ChatMessageVO> toMessageVOs(Long userId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<Long> messageIds = messages.stream()
                .map(ChatMessage::getId)
                .filter(Objects::nonNull)
                .toList();

        List<ChatReference> references = messageIds.isEmpty()
                ? List.of()
                : chatReferenceMapper.selectList(
                        new QueryWrapper<ChatReference>()
                                .in("message_id", messageIds)
                                .orderByAsc("id")
                );

        // 引用片段：按 chunkId 读取文本块原文
        Set<String> chunkIds = references.stream()
                .map(ChatReference::getChunkId)
                .filter(chunkId -> chunkId != null && !chunkId.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Map<String, String> chunkContents = new HashMap<>();
        if (!chunkIds.isEmpty()) {
            List<DocumentChunk> chunks = documentChunkMapper.selectList(
                    new QueryWrapper<DocumentChunk>()
                            .select("chunk_id", "content")
                            .in("chunk_id", chunkIds)
            );
            for (DocumentChunk chunk : chunks) {
                chunkContents.put(chunk.getChunkId(), chunk.getContent());
            }
        }

        // 文献标题：经 DocumentAccessService 校验归属后读取
        Set<Long> documentIds = references.stream()
                .map(ChatReference::getDocumentId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Map<Long, String> documentTitles = new HashMap<>();
        if (!documentIds.isEmpty()) {
            for (Document document : documentAccessService.listOwned(userId, documentIds)) {
                documentTitles.put(document.getId(), document.getTitle());
            }
        }

        Map<Long, List<ChatReference>> referencesByMessage = new HashMap<>();
        for (ChatReference reference : references) {
            referencesByMessage
                    .computeIfAbsent(reference.getMessageId(), key -> new ArrayList<>())
                    .add(reference);
        }

        List<ChatMessageVO> result = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            ChatMessageVO vo = ChatConvert.toVO(message);
            if (vo == null) {
                continue;
            }

            List<ChatReferenceVO> referenceVOs = new ArrayList<>();
            for (ChatReference reference : referencesByMessage.getOrDefault(message.getId(), List.of())) {
                referenceVOs.add(ChatConvert.toVO(
                        reference,
                        documentTitles.get(reference.getDocumentId()),
                        chunkContents.get(reference.getChunkId())
                ));
            }
            vo.setReferences(referenceVOs);
            result.add(vo);
        }
        return result;
    }

    /**
     * 把 AI 返回的引用转换为实体，并过滤掉不属于当前用户的文献
     */
    private List<ChatReference> buildReferences(Long userId, List<AiResults.Reference> references) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }

        Set<Long> requestedDocumentIds = references.stream()
                .map(AiResults.Reference::documentId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Map<Long, Document> ownedDocuments = new HashMap<>();
        if (!requestedDocumentIds.isEmpty()) {
            for (Document document : documentAccessService.listOwned(userId, requestedDocumentIds)) {
                ownedDocuments.put(document.getId(), document);
            }
        }

        List<ChatReference> result = new ArrayList<>();
        Set<String> dedupeKeys = new HashSet<>();
        for (AiResults.Reference hit : references) {
            if (hit == null || hit.documentId() == null || hit.chunkId() == null) {
                continue;
            }

            Document document = ownedDocuments.get(hit.documentId());
            if (document == null) {
                continue;
            }

            // 回查 MySQL：引用必须属于当前用户、该文献、且属于当前有效解析版本
            DocumentChunk chunk = documentChunkMapper.selectOne(
                    new LambdaQueryWrapper<DocumentChunk>()
                            .eq(DocumentChunk::getChunkId, hit.chunkId())
                            .eq(DocumentChunk::getUserId, userId)
                            .last("LIMIT 1"));

            if (chunk == null
                    || !Objects.equals(chunk.getDocumentId(), hit.documentId())
                    || !Objects.equals(chunk.getParseRecordId(), document.getCurrentParseRecordId())) {
                continue;
            }

            String dedupeKey = hit.documentId() + "#" + hit.chunkId();
            if (!dedupeKeys.add(dedupeKey)) {
                continue;
            }

            ChatReference reference = new ChatReference();
            reference.setDocumentId(hit.documentId());
            // 字段长度与 DDL 对齐，避免 AI 返回超长值导致写入失败
            reference.setChunkId(truncate(hit.chunkId(), 64));
            reference.setPageNumber(chunk.getPageStart() == null ? hit.pageNumber() : chunk.getPageStart());
            reference.setSection(truncate(
                    chunk.getSection() == null ? hit.section() : chunk.getSection(), 255));
            reference.setRelevanceScore(hit.relevanceScore());
            reference.setCreatedAt(LocalDateTime.now());
            result.add(reference);
        }
        return result;
    }

    /**
     * Token 用量：{prompt, completion}
     */
    private Map<String, Object> tokenUsageOf(Integer promptTokens, Integer completionTokens) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt", promptTokens);
        usage.put("completion", completionTokens);
        return usage;
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
}
