package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatHandler chatHandler;
    private final JwtUtils jwtUtils;

    public ChatController(ChatHandler chatHandler, JwtUtils jwtUtils) {
        this.chatHandler = chatHandler;
        this.jwtUtils = jwtUtils;
    }

    @PostMapping(value = "/stream/{token}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@PathVariable String token, @RequestBody ChatStreamRequest request) {
        String username = jwtUtils.extractUsernameFromToken(token);
        String userId = (username == null || username.isBlank()) ? token : username;

        SseEmitter emitter = new SseEmitter(0L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(emitter::completeWithError);

        CompletableFuture.runAsync(() ->
            chatHandler.processMessageSse(userId, request.getMessage(), request.getConversationId(), emitter)
        );

        return emitter;
    }

    public static class ChatStreamRequest {
        private String message;
        private String conversationId;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getConversationId() {
            return conversationId;
        }

        public void setConversationId(String conversationId) {
            this.conversationId = conversationId;
        }
    }
}
