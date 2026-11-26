package com.research.platform.modules.chat.service;

import com.research.platform.modules.chat.dto.ChatSendDTO;
import com.research.platform.modules.chat.dto.ConversationCreateDTO;
import com.research.platform.modules.chat.vo.ChatMessageVO;
import com.research.platform.modules.chat.vo.ConversationVO;

import java.util.List;
import java.util.Map;

/**
 * 科研问答服务
 */
public interface IChatService {

    /**
     * 当前用户的问答会话列表（按更新时间倒序）
     */
    List<ConversationVO> listConversations();

    /**
     * 新建会话
     */
    ConversationVO createConversation(ConversationCreateDTO dto);

    /**
     * 逻辑删除会话（消息与引用保留）
     */
    void deleteConversation(Long conversationId);

    /**
     * 会话内消息（含引用来源）
     */
    List<ChatMessageVO> listMessages(Long conversationId);

    /**
     * 发送问题并生成回答
     *
     * @return conversationId / userMessage / assistantMessage
     */
    Map<String, Object> sendMessage(ChatSendDTO dto);
}
