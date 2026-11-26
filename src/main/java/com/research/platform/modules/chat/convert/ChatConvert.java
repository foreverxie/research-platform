package com.research.platform.modules.chat.convert;

import com.research.platform.modules.chat.entity.ChatMessage;
import com.research.platform.modules.chat.entity.ChatReference;
import com.research.platform.modules.chat.entity.Conversation;
import com.research.platform.modules.chat.vo.ChatMessageVO;
import com.research.platform.modules.chat.vo.ChatReferenceVO;
import com.research.platform.modules.chat.vo.ConversationVO;

import java.util.ArrayList;

/**
 * 问答模块实体与视图转换
 */
public final class ChatConvert {

    /**
     * 引用片段的最大长度
     */
    private static final int SNIPPET_LENGTH = 160;

    private ChatConvert() {
    }

    /**
     * 会话实体转视图
     */
    public static ConversationVO toVO(Conversation conversation, long messageCount) {
        if (conversation == null) {
            return null;
        }

        ConversationVO vo = new ConversationVO();
        vo.setId(conversation.getId());
        vo.setUserId(conversation.getUserId());
        vo.setTitle(conversation.getTitle());
        vo.setMessageCount(messageCount);
        vo.setCreatedAt(conversation.getCreatedAt());
        vo.setUpdatedAt(conversation.getUpdatedAt());
        return vo;
    }

    /**
     * 消息实体转视图（引用由调用方补齐）
     */
    public static ChatMessageVO toVO(ChatMessage message) {
        if (message == null) {
            return null;
        }

        ChatMessageVO vo = new ChatMessageVO();
        vo.setId(message.getId());
        vo.setConversationId(message.getConversationId());
        vo.setRole(message.getRole());
        vo.setContent(message.getContent());
        vo.setModelName(message.getModelName());
        vo.setTokenUsage(message.getTokenUsage());
        vo.setReferences(new ArrayList<>());
        vo.setCreatedAt(message.getCreatedAt());
        return vo;
    }

    /**
     * 引用实体转视图
     *
     * @param documentTitle 文献标题，可为 null
     * @param chunkContent  文本块原文，可为 null
     */
    public static ChatReferenceVO toVO(ChatReference reference, String documentTitle, String chunkContent) {
        if (reference == null) {
            return null;
        }

        ChatReferenceVO vo = new ChatReferenceVO();
        vo.setId(reference.getId());
        vo.setMessageId(reference.getMessageId());
        vo.setDocumentId(reference.getDocumentId());
        vo.setDocumentTitle(documentTitle);
        vo.setChunkId(reference.getChunkId());
        vo.setPageNumber(reference.getPageNumber());
        vo.setSection(reference.getSection());
        vo.setRelevanceScore(reference.getRelevanceScore());
        vo.setSnippet(snippetOf(chunkContent));
        return vo;
    }

    /**
     * 取文本前 160 字作为引用片段
     */
    public static String snippetOf(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        String text = content.trim();
        return text.length() > SNIPPET_LENGTH
                ? text.substring(0, SNIPPET_LENGTH) + "…"
                : text;
    }
}
