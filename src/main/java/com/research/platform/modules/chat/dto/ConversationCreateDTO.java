package com.research.platform.modules.chat.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建问答会话请求
 */
@Data
public class ConversationCreateDTO {

    /**
     * 会话标题，为空时使用默认标题
     */
    @Size(max = 100, message = "会话标题不能超过 100 个字符")
    private String title;
}
