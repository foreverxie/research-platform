package com.research.platform.modules.chat.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "chat_message", autoResultMap = true)
public class ChatMessage {

    /**
     * 消息ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属会话ID
     */
    @TableField("conversation_id")
    private Long conversationId;

    /**
     * 消息角色：USER/ASSISTANT/SYSTEM
     */
    @TableField("role")
    private String role;

    /**
     * 消息内容
     */
    @TableField("content")
    private String content;

    /**
     * 模型名称
     */
    @TableField("model_name")
    private String modelName;

    /**
     * Token用量
     */
    @TableField(value = "token_usage", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> tokenUsage;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
