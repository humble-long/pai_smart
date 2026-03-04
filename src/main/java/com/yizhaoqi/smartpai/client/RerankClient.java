package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 重排序客户端
 * 调用 DashScope compatible-mode /rerank 接口（Cohere 兼容格式）
 * 对 RRF 融合后的候选文档进行精细化打分，输出最终排序结果。
 */
@Component
public class RerankClient {

    private static final Logger logger = LoggerFactory.getLogger(RerankClient.class);

    @Value("${rerank.api.model:gte-rerank}")
    private String model;

    @Value("${rerank.api.top-n:5}")
    private int topN;

    @Value("${rerank.api.enabled:true}")
    private boolean enabled;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public RerankClient(WebClient rerankWebClient, ObjectMapper objectMapper) {
        this.webClient = rerankWebClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 对候选文档列表进行重排序。
     * 如果重排序未启用或调用失败，直接截取前 topK 条原始结果返回（保底降级）。
     *
     * @param query      用户原始查询（改写后）
     * @param candidates RRF 融合后的候选文档
     * @param topK       最终返回条数
     * @return 重排序后的文档列表
     */
    public List<SearchResult> rerank(String query, List<SearchResult> candidates, int topK) {
        if (!enabled) {
            logger.debug("重排序未启用，直接截取前 {} 条候选结果", topK);
            return candidates.stream().limit(topK).collect(Collectors.toList());
        }
        if (candidates.isEmpty()) {
            return candidates;
        }

        try {
            // 提取每个分块的文本内容作为 documents
            List<String> documents = candidates.stream()
                    .map(SearchResult::getTextContent)
                    .collect(Collectors.toList());

            // 构建 Cohere 兼容的 rerank 请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("query", query);
            requestBody.put("documents", documents);
            requestBody.put("top_n", Math.min(topK, candidates.size()));
            requestBody.put("return_documents", false); // 不需要返回文档原文，减少流量

            logger.debug("调用重排序 API，候选数量: {}, top_n: {}", candidates.size(), topK);

            String responseBody = webClient.post()
                    .uri("/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            if (responseBody == null) {
                logger.warn("重排序 API 返回为空，降级为原始顺序");
                return candidates.stream().limit(topK).collect(Collectors.toList());
            }

            // 解析响应：results 数组，每项含 index（对应候选列表下标）和 relevance_score
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode results = root.path("results");

            if (!results.isArray() || results.isEmpty()) {
                logger.warn("重排序 API 返回格式异常: {}", responseBody);
                return candidates.stream().limit(topK).collect(Collectors.toList());
            }

            List<SearchResult> reranked = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                double score = item.path("relevance_score").asDouble(0.0);
                if (index < 0 || index >= candidates.size()) continue;

                SearchResult result = candidates.get(index);
                result.setScore(score); // 用重排序分替换 RRF 分
                reranked.add(result);
            }

            logger.info("重排序完成，输出 {} 条结果", reranked.size());
            return reranked;

        } catch (Exception e) {
            logger.error("重排序失败，降级为原始顺序: {}", e.getMessage());
            return candidates.stream().limit(topK).collect(Collectors.toList());
        }
    }
}
