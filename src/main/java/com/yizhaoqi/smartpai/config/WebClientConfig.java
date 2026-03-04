package com.yizhaoqi.smartpai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;

@Configuration
public class WebClientConfig {

    @Value("${embedding.api.url}")
    private String embeddingApiUrl;

    @Value("${embedding.api.key}")
    private String embeddingApiKey;

    @Value("${rerank.api.url:${embedding.api.url}}")
    private String rerankApiUrl;

    @Value("${rerank.api.key:${embedding.api.key}}")
    private String rerankApiKey;

    @Bean
    public WebClient embeddingWebClient() {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
            .codecs(configurer -> configurer
                .defaultCodecs()
                .maxInMemorySize(16 * 1024 * 1024)) // 16MB
            .build();

        return WebClient.builder()
            .baseUrl(embeddingApiUrl)
            .exchangeStrategies(strategies)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + embeddingApiKey)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    /**
     * 重排序服务 WebClient
     * 默认复用 DashScope 同一 base URL 和 key（与 embedding 同平台）
     */
    @Bean
    public WebClient rerankWebClient() {
        return WebClient.builder()
            .baseUrl(rerankApiUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + rerankApiKey)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }
} 