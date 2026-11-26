package com.research.platform.infrastructure.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * FastAPI AI 服务客户端实现
 *
 * <p>调用失败统一抛出 {@link AiServiceException}，由任务执行器记录失败原因；
 * 普通业务接口不会因为 AI 服务不可用而不可用。</p>
 */
@Component
public class FastApiAiServiceClient implements AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(FastApiAiServiceClient.class);

    private static final String PARSE_PATH = "/internal/v1/documents/parse";
    private static final String ANALYZE_PATH = "/internal/v1/documents/analyze";
    private static final String ASK_PATH = "/internal/v1/rag/answer";
    private static final String SEARCH_PATH = "/internal/v1/search/semantic";
    private static final String INDEX_PATH = "/internal/v1/embeddings/index";
    private static final String METADATA_PATH = "/internal/v1/documents/metadata";
    private static final String CLEANUP_PATH = "/internal/v1/embeddings/cleanup";
    private static final String KNOWLEDGE_PATH = "/internal/v1/knowledge/extract";
    private static final String HEALTH_PATH = "/internal/v1/health";

    private final AiProperties properties;
    private final RestClient restClient;

    public FastApiAiServiceClient(AiProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(properties.getConnectTimeoutSeconds()).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(properties.getReadTimeoutSeconds()).toMillis());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(stripTrailingSlash(properties.getBaseUrl()))
                .requestFactory(factory);

        if (properties.getInternalToken() != null && !properties.getInternalToken().isBlank()) {
            builder.defaultHeader("X-Internal-Token", properties.getInternalToken());
        }

        this.restClient = builder.build();
    }

    @Override
    public AiResults.Parse parse(AiCommands.Parse command) {
        requireEnabled();
        return call(PARSE_PATH, command, AiResults.Parse.class);
    }

    @Override
    public AiResults.Analysis analyze(AiCommands.Analyze command) {
        requireEnabled();
        return call(ANALYZE_PATH, command, AiResults.Analysis.class);
    }

    @Override
    public AiResults.Answer ask(AiCommands.Ask command) {
        requireEnabled();
        return call(ASK_PATH, command, AiResults.Answer.class);
    }

    @Override
    public AiResults.Search semanticSearch(AiCommands.SemanticSearch command) {
        requireEnabled();
        return call(SEARCH_PATH, command, AiResults.Search.class);
    }

    @Override
    public AiResults.Index indexChunks(AiCommands.Index command) {
        requireEnabled();
        return call(INDEX_PATH, command, AiResults.Index.class);
    }

    @Override
    public AiResults.Metadata extractMetadata(AiCommands.Metadata command) {
        requireEnabled();
        return call(METADATA_PATH, command, AiResults.Metadata.class);
    }

    @Override
    public AiResults.Cleanup cleanupVectors(AiCommands.Cleanup command) {
        requireEnabled();
        return call(CLEANUP_PATH, command, AiResults.Cleanup.class);
    }

    @Override
    public AiResults.Health health() {
        if (!properties.isEnabled()) {
            return null;
        }

        try {
            return restClient.get()
                    .uri(HEALTH_PATH)
                    .retrieve()
                    .body(AiResults.Health.class);
        } catch (Exception e) {
            log.warn("读取 AI 服务健康信息失败：{}", e.getMessage());
            return null;
        }
    }

    @Override
    public AiResults.Knowledge extractKnowledge(AiCommands.Knowledge command) {
        requireEnabled();
        return call(KNOWLEDGE_PATH, command, AiResults.Knowledge.class);
    }

    @Override
    public boolean ping() {
        if (!properties.isEnabled()) {
            return false;
        }

        try {
            restClient.get().uri(HEALTH_PATH).retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("AI 服务健康检查失败：{}", e.getMessage());
            return false;
        }
    }

    private <T> T call(String path, Object body, Class<T> responseType) {
        try {
            T result = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(responseType);

            if (result == null) {
                throw new AiServiceException("AI 服务返回空响应：" + path);
            }

            return result;
        } catch (AiServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 AI 服务失败：{}", path, e);
            throw new AiServiceException("AI 服务调用失败（" + path + "）：" + e.getMessage(), e);
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new AiServiceException("AI 服务未启用（research.ai.enabled=false），请先部署 FastAPI 服务");
        }
    }

    private String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:8000";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
