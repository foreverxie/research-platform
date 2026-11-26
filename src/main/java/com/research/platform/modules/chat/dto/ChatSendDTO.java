package com.research.platform.modules.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发起科研问答请求
 */
@Data
public class ChatSendDTO {

    /**
     * 会话ID；为空或不属于当前用户时新建会话
     */
    private Long conversationId;

    /**
     * 问题内容
     */
    @NotBlank(message = "问题内容不能为空")
    @Size(max = 2000, message = "问题内容不能超过 2000 个字符")
    private String content;

    /**
     * 限定检索的文献范围；为空表示整个个人文献库
     */
    private List<Long> documentIds;
}
