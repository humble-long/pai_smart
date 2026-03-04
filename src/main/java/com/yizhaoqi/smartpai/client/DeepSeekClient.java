package com.yizhaoqi.smartpai.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.model.IntentType;

@Service
public class DeepSeekClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final AiProperties aiProperties;
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);
    
    public DeepSeekClient(@Value("${deepseek.api.url}") String apiUrl,
                         @Value("${deepseek.api.key}") String apiKey,
                         @Value("${deepseek.api.model}") String model,
                         AiProperties aiProperties) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);
        
        // 只有当 API key 不为空时才添加 Authorization header
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        
        this.webClient = builder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.aiProperties = aiProperties;
    }
    
    public void streamResponse(String userMessage, 
                             String context,
                             List<Map<String, String>> history,
                             Consumer<String> onChunk,
                             Consumer<Throwable> onError) {
        
        Map<String, Object> request = buildRequest(userMessage, context, history);
        
        webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                    chunk -> processChunk(chunk, onChunk),
                    onError
                );
    }
    
    private Map<String, Object> buildRequest(String userMessage, 
                                           String context,
                                           List<Map<String, String>> history) {
        logger.info("构建请求，用户消息：{}，上下文长度：{}，历史消息数：{}", 
                   userMessage, 
                   context != null ? context.length() : 0, 
                   history != null ? history.size() : 0);
        
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        request.put("stream", true);
        // 生成参数
        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }
    
    private List<Map<String, String>> buildMessages(String userMessage,
                                                  String context,
                                                  List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();

        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        // 1. 构建统一的 system 指令（规则 + 参考信息）
        StringBuilder sysBuilder = new StringBuilder();
        String rules = promptCfg.getRules();
        if (rules != null) {
            sysBuilder.append(rules).append("\n\n");
        }

        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");

        if (context != null && !context.isEmpty()) {
            sysBuilder.append(context);
        } else {
            String noResult = promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无检索结果）";
            sysBuilder.append(noResult).append("\n");
        }

        sysBuilder.append(refEnd);

        String systemContent = sysBuilder.toString();
        messages.add(Map.of(
            "role", "system",
            "content", systemContent
        ));
        logger.debug("添加了系统消息，长度: {}", systemContent.length());

        // 2. 追加历史消息（若有）
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        // 3. 当前用户问题
        messages.add(Map.of(
            "role", "user",
            "content", userMessage
        ));

        return messages;
    }
    
    /**
     * 同步调用 LLM 对用户问题进行改写，返回改写后的查询字符串。
     * 失败时返回原始问题，保证主流程不中断。
     *
     * @param userMessage 用户原始问题
     * @param history     对话历史（用于补全指代词）
     * @return 改写后的查询字符串
     */
    public String rewriteQuery(String userMessage, List<Map<String, String>> history) {
        try {
            String systemPrompt = aiProperties.getPrompt().getQueryRewritePrompt();
            if (systemPrompt == null || systemPrompt.isBlank()) {
                logger.debug("未配置 query-rewrite-prompt，跳过问题改写");
                return userMessage;
            }

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt.strip()));

            // 最多携带最近 6 条历史，避免 token 浪费
            if (history != null && !history.isEmpty()) {
                int start = Math.max(0, history.size() - 6);
                messages.addAll(history.subList(start, history.size()));
            }
            messages.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> request = new java.util.HashMap<>();
            request.put("model", model);
            request.put("messages", messages);
            request.put("stream", false);
            request.put("temperature", 0.0);  // 改写任务要确定性输出
            request.put("max_tokens", 128);   // 改写结果不需要太长

            String responseBody = webClient.post()
                    .uri("/chat/completions")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(15));

            if (responseBody == null) {
                logger.warn("问题改写 API 返回为空，使用原始问题");
                return userMessage;
            }

            ObjectMapper mapper = new ObjectMapper();
            String rewritten = mapper.readTree(responseBody)
                    .path("choices").path(0)
                    .path("message").path("content")
                    .asText("").strip();

            if (rewritten.isEmpty()) {
                logger.warn("问题改写结果为空，使用原始问题");
                return userMessage;
            }

            logger.info("问题改写完成 | 原始: [{}] → 改写: [{}]", userMessage, rewritten);
            return rewritten;
        } catch (Exception e) {
            logger.error("问题改写失败，使用原始问题: {}", e.getMessage());
            return userMessage;
        }
    }

    /**
     * 同步调用 LLM 识别用户问题的意图类型。
     * 失败时默认返回 KNOWLEDGE_BASE，保证主流程不中断。
     *
     * @param userMessage 用户原始问题（改写前）
     * @param history     对话历史
     * @return 意图类型 {@link IntentType}
     */
    public IntentType recognizeIntent(String userMessage, List<Map<String, String>> history) {
        try {
            String systemPrompt = aiProperties.getPrompt().getIntentRecognitionPrompt();
            if (systemPrompt == null || systemPrompt.isBlank()) {
                logger.debug("未配置 intent-recognition-prompt，跳过意图识别，默认 KNOWLEDGE_BASE");
                return IntentType.KNOWLEDGE_BASE;
            }

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt.strip()));

            // 携带最近 4 条历史，意图识别只需少量上下文
            if (history != null && !history.isEmpty()) {
                int start = Math.max(0, history.size() - 4);
                messages.addAll(history.subList(start, history.size()));
            }
            messages.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> request = new java.util.HashMap<>();
            request.put("model", model);
            request.put("messages", messages);
            request.put("stream", false);
            request.put("temperature", 0.0);
            request.put("max_tokens", 20);  // 意图标签极短，限制输出

            String responseBody = webClient.post()
                    .uri("/chat/completions")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(10));

            if (responseBody == null) {
                logger.warn("意图识别 API 返回为空，默认 KNOWLEDGE_BASE");
                return IntentType.KNOWLEDGE_BASE;
            }

            ObjectMapper mapper = new ObjectMapper();
            String raw = mapper.readTree(responseBody)
                    .path("choices").path(0)
                    .path("message").path("content")
                    .asText("").strip();

            IntentType intent = IntentType.parse(raw);
            logger.info("意图识别完成 | 问题: [{}] → 意图: [{}]（原始输出: {}）", userMessage, intent, raw);
            return intent;
        } catch (Exception e) {
            logger.error("意图识别失败，默认 KNOWLEDGE_BASE: {}", e.getMessage());
            return IntentType.KNOWLEDGE_BASE;
        }
    }

    private void processChunk(String chunk, Consumer<String> onChunk) {
        try {
            // 检查是否是结束标记
            if ("[DONE]".equals(chunk)) {
                logger.debug("对话结束");
                return;
            }
            
            // 直接解析 JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(chunk);
            String content = node.path("choices")
                               .path(0)
                               .path("delta")
                               .path("content")
                               .asText("");
            
            if (!content.isEmpty()) {
                onChunk.accept(content);
            }
        } catch (Exception e) {
            logger.error("处理数据块时出错: {}", e.getMessage(), e);
        }
    }
} 