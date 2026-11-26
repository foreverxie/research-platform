package com.research.platform.modules.chat.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 问答消息视图
 */
@Data
public class ChatMessageVO {

    private Long id;

    private Long conversationId;

    /**
     * USER / ASSISTANT / SYSTEM
     */
    private String role;

    private String content;

    /**
     * 生成该条消息的模型名称；系统兜底消息为 system
     */
    private String modelName;

    /**
     * Token 用量：{prompt, completion}
     */
    private Map<String, Object> tokenUsage;

    /**
     * 引用来源，永不为 null
     */
    private List<ChatReferenceVO> references;

    private LocalDateTime createdAt;
}
