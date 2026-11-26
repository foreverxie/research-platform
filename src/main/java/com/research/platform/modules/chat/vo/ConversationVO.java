package com.research.platform.modules.chat.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答会话视图
 */
@Data
public class ConversationVO {

    private Long id;

    private Long userId;

    private String title;

    /**
     * 该会话下的消息条数（chat_message 计数）
     */
    private Long messageCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
