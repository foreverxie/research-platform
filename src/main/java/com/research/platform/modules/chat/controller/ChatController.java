package com.research.platform.modules.chat.controller;

import com.research.platform.common.result.Result;
import com.research.platform.modules.chat.dto.ChatSendDTO;
import com.research.platform.modules.chat.dto.ConversationCreateDTO;
import com.research.platform.modules.chat.service.IChatService;
import com.research.platform.modules.chat.vo.ChatMessageVO;
import com.research.platform.modules.chat.vo.ConversationVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 科研问答接口
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final IChatService chatService;

    public ChatController(IChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 会话列表
     */
    @GetMapping("/sessions")
    public Result<List<ConversationVO>> listSessions() {
        return Result.success(chatService.listConversations());
    }

    /**
     * 新建会话
     */
    @PostMapping("/sessions")
    public Result<ConversationVO> createSession(@Valid @RequestBody(required = false) ConversationCreateDTO dto) {
        return Result.success(chatService.createConversation(dto == null ? new ConversationCreateDTO() : dto));
    }

    /**
     * 删除会话（逻辑删除）
     */
    @DeleteMapping("/sessions/{id}")
    public Result<Void> deleteSession(@PathVariable("id") Long id) {
        chatService.deleteConversation(id);
        return Result.success(null);
    }

    /**
     * 会话内消息
     */
    @GetMapping("/sessions/{id}/messages")
    public Result<List<ChatMessageVO>> listMessages(@PathVariable("id") Long id) {
        return Result.success(chatService.listMessages(id));
    }

    /**
     * 发送问题
     */
    @PostMapping("/messages")
    public Result<Map<String, Object>> sendMessage(@Valid @RequestBody ChatSendDTO dto) {
        return Result.success(chatService.sendMessage(dto));
    }
}
