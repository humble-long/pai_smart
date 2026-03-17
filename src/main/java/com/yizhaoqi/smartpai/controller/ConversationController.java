package com.yizhaoqi.smartpai.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/users")
public class ConversationController {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------------------------------------------------------------
    // 1. 查询当前用户所有会话列表
    // GET /api/v1/users/conversations
    // ---------------------------------------------------------------
    @GetMapping("/conversations")
    public ResponseEntity<?> listConversations(
            @RequestHeader("Authorization") String token) {
        try {
            String username = extractUsername(token);
            String userId = resolveUserId(username);
            String listKey = "user:" + userId + ":conversations";

            // 按创建时间倒序取所有会话 ID
            Set<String> convIds = redisTemplate.opsForZSet()
                    .reverseRange(listKey, 0, -1);
            if (convIds == null) convIds = Collections.emptySet();

            List<Map<String, Object>> result = new ArrayList<>();
            for (String convId : convIds) {
                Map<Object, Object> meta = redisTemplate.opsForHash()
                        .entries("conversation:" + convId + ":meta");
                Map<String, Object> item = new HashMap<>();
                item.put("conversationId", convId);
                item.put("title", meta.getOrDefault("title", "新对话"));
                item.put("createdAt", meta.getOrDefault("createdAt", ""));
                result.add(item);
            }

            return ok("获取会话列表成功", result);
        } catch (CustomException e) {
            return err(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // 2. 创建新会话
    // POST /api/v1/users/conversations
    // ---------------------------------------------------------------
    @PostMapping("/conversations")
    public ResponseEntity<?> createConversation(
            @RequestHeader("Authorization") String token) {
        try {
            String username = extractUsername(token);
            String userId = resolveUserId(username);
            String listKey = "user:" + userId + ":conversations";

            String conversationId = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            redisTemplate.opsForZSet().add(listKey, conversationId, now);
            redisTemplate.expire(listKey, Duration.ofDays(30));
            redisTemplate.opsForHash().put(
                    "conversation:" + conversationId + ":meta", "createdAt", String.valueOf(now));
            redisTemplate.expire("conversation:" + conversationId + ":meta", Duration.ofDays(7));

            Map<String, Object> data = new HashMap<>();
            data.put("conversationId", conversationId);
            data.put("title", "新对话");
            data.put("createdAt", now);
            return ok("创建会话成功", data);
        } catch (CustomException e) {
            return err(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // 3. 删除会话
    // DELETE /api/v1/users/conversations/{id}
    // ---------------------------------------------------------------
    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<?> deleteConversation(
            @RequestHeader("Authorization") String token,
            @PathVariable("id") String conversationId) {
        try {
            String username = extractUsername(token);
            String userId = resolveUserId(username);
            String listKey = "user:" + userId + ":conversations";

            // 校验归属
            Double score = redisTemplate.opsForZSet().score(listKey, conversationId);
            if (score == null) {
                throw new CustomException("会话不存在或无权限删除", HttpStatus.NOT_FOUND);
            }

            redisTemplate.opsForZSet().remove(listKey, conversationId);
            redisTemplate.delete("conversation:" + conversationId);
            redisTemplate.delete("conversation:" + conversationId + ":meta");

            return ok("删除会话成功", null);
        } catch (CustomException e) {
            return err(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // 4. 查询指定会话的消息历史（支持时间过滤）
    // GET /api/v1/users/conversations/{id}/messages
    // ---------------------------------------------------------------
    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<?> getConversationMessages(
            @RequestHeader("Authorization") String token,
            @PathVariable("id") String conversationId,
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date) {
        try {
            String username = extractUsername(token);
            String userId = resolveUserId(username);
            // 校验归属
            Double score = redisTemplate.opsForZSet().score(
                    "user:" + userId + ":conversations", conversationId);
            if (score == null) {
                throw new CustomException("会话不存在或无权限访问", HttpStatus.NOT_FOUND);
            }
            List<Map<String, Object>> messages = loadMessages(conversationId, start_date, end_date, username);
            return ok("获取消息历史成功", messages);
        } catch (CustomException e) {
            return err(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // 5. 兼容旧接口：查询当前活跃会话历史（向后兼容）
    // GET /api/v1/users/conversation   （保留单数形式）
    // ---------------------------------------------------------------
    @GetMapping("/conversation")
    public ResponseEntity<?> getActiveConversation(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String start_date,
            @RequestParam(required = false) String end_date) {
        try {
            String username = extractUsername(token);
            String userId = resolveUserId(username);
            // 取最新的一个会话
            Set<String> latest = redisTemplate.opsForZSet()
                    .reverseRange("user:" + userId + ":conversations", 0, 0);
            if (latest == null || latest.isEmpty()) {
                return ok("获取对话历史成功", Collections.emptyList());
            }
            String conversationId = latest.iterator().next();
            List<Map<String, Object>> messages = loadMessages(conversationId, start_date, end_date, username);
            return ok("获取对话历史成功", messages);
        } catch (CustomException e) {
            return err(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // 私有工具方法
    // ---------------------------------------------------------------

    private String extractUsername(String bearerToken) {
        String raw = bearerToken.startsWith("Bearer ") ? bearerToken.substring(7) : bearerToken;
        String username = jwtUtils.extractUsernameFromToken(raw);
        if (username == null || username.isBlank()) {
            throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
        }
        return username;
    }

    private String resolveUserId(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        return user.getId().toString();
    }

    private List<Map<String, Object>> loadMessages(String conversationId,
                                                    String startDate, String endDate,
                                                    String username) {
        String json = redisTemplate.opsForValue().get("conversation:" + conversationId);
        if (json == null) return Collections.emptyList();

        List<Map<String, String>> history;
        try {
            history = objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            LogUtils.logBusinessError("GET_MESSAGES", username, "解析历史失败", e);
            throw new CustomException("解析消息历史失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        LocalDateTime startDt = startDate != null ? parseDateTime(startDate) : null;
        LocalDateTime endDt = endDate != null ? parseDateTime(endDate) : null;

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, String> msg : history) {
            String ts = msg.getOrDefault("timestamp", "");
            if ((startDt != null || endDt != null) && !ts.isEmpty()) {
                try {
                    LocalDateTime msgDt = LocalDateTime.parse(ts,
                            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
                    if (startDt != null && msgDt.isBefore(startDt)) continue;
                    if (endDt != null && msgDt.isAfter(endDt)) continue;
                } catch (Exception ignored) {}
            }
            Map<String, Object> item = new HashMap<>();
            item.put("role", msg.get("role"));
            item.put("content", msg.get("content"));
            item.put("timestamp", ts);
            result.add(item);
        }
        return result;
    }

    private LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDateTime.parse(s); } catch (DateTimeParseException ignored) {}
        if (s.length() == 16) return LocalDateTime.parse(s + ":00");
        if (s.length() == 10) return LocalDateTime.parse(s + "T00:00:00");
        throw new CustomException("无效的日期格式: " + s, HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<Map<String, Object>> ok(String message, Object data) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", 200);
        body.put("message", message);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> err(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
