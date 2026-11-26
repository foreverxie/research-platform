package com.research.platform.modules.system.service.impl;

import com.research.platform.infrastructure.ai.AiProperties;
import com.research.platform.infrastructure.ai.AiResults;
import com.research.platform.infrastructure.ai.AiServiceClient;
import com.research.platform.infrastructure.storage.StorageProperties;
import com.research.platform.modules.system.service.ISystemService;
import com.research.platform.modules.system.vo.SystemHealthVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统状态服务实现
 *
 * <p>Spring Boot 只直接探测自身、MySQL、FastAPI 与文件存储；
 * Milvus、MinerU、Embedding 由 FastAPI 内部管理，这里标记为 DEGRADED。</p>
 */
@Service
public class SystemServiceImpl implements ISystemService {

    private static final Logger log = LoggerFactory.getLogger(SystemServiceImpl.class);

    private static final String UP = "UP";

    private static final String DOWN = "DOWN";

    private static final String DEGRADED = "DEGRADED";

    private static final int MAX_DETAIL_LENGTH = 300;

    private final JdbcTemplate jdbcTemplate;
    private final AiServiceClient aiServiceClient;
    private final AiProperties aiProperties;
    private final StorageProperties storageProperties;

    public SystemServiceImpl(
            JdbcTemplate jdbcTemplate,
            AiServiceClient aiServiceClient,
            AiProperties aiProperties,
            StorageProperties storageProperties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.aiServiceClient = aiServiceClient;
        this.aiProperties = aiProperties;
        this.storageProperties = storageProperties;
    }

    @Override
    public SystemHealthVO health() {
        AiResults.Health aiHealth = aiProperties.isEnabled() ? aiServiceClient.health() : null;

        List<SystemHealthVO.ServiceHealth> services = new ArrayList<>(7);
        services.add(springHealth());
        services.add(fastApiHealth());
        services.add(mysqlHealth());
        services.add(milvusHealth(aiHealth));
        services.add(mineruHealth(aiHealth));
        services.add(embeddingHealth(aiHealth));
        services.add(storageHealth());

        SystemHealthVO health = new SystemHealthVO();
        health.setCheckedAt(LocalDateTime.now());
        health.setServices(services);
        return health;
    }

    private SystemHealthVO.ServiceHealth milvusHealth(AiResults.Health aiHealth) {
        if (aiHealth != null && "milvus".equalsIgnoreCase(aiHealth.vectorStore())) {
            return service("Milvus 向量库", "milvus", UP, "Milvus 已连接，向量检索可用", 0L);
        }

        String detail = aiHealth == null
                ? "AI 服务健康信息不可用"
                : "当前使用本地向量库（" + aiHealth.vectorStore() + "）";
        return degradedService("Milvus 向量库", "milvus", detail);
    }

    private SystemHealthVO.ServiceHealth mineruHealth(AiResults.Health aiHealth) {
        String parser = aiHealth == null ? null : aiHealth.parser();

        if (parser != null && parser.toLowerCase().startsWith("mineru")) {
            return service("MinerU 解析组件", "mineru", UP, "解析器：" + parser, 0L);
        }

        return degradedService(
                "MinerU 解析组件",
                "mineru",
                parser == null ? "AI 服务健康信息不可用" : "当前解析器：" + parser
        );
    }

    private SystemHealthVO.ServiceHealth embeddingHealth(AiResults.Health aiHealth) {
        String embedding = aiHealth == null ? null : aiHealth.embedding();

        if (embedding != null && !"local".equalsIgnoreCase(embedding)) {
            return service("Embedding 服务", "embedding", UP, "Embedding：" + embedding, 0L);
        }

        return degradedService(
                "Embedding 服务",
                "embedding",
                embedding == null ? "AI 服务健康信息不可用" : "当前为本地哈希向量（" + embedding + "）"
        );
    }

    /**
     * 业务服务自身
     */
    private SystemHealthVO.ServiceHealth springHealth() {
        return service("Spring Boot 业务服务", "spring", UP, "业务服务运行中", 0L);
    }

    /**
     * FastAPI：只暴露地址与启用状态，不泄露服务间令牌
     */
    private SystemHealthVO.ServiceHealth fastApiHealth() {
        String baseUrl = aiProperties.getBaseUrl() == null ? "未配置" : aiProperties.getBaseUrl();

        if (!aiProperties.isEnabled()) {
            return service(
                    "FastAPI AI 服务",
                    "fastapi",
                    DOWN,
                    "AI 服务未启用（research.ai.enabled=false），地址：" + baseUrl,
                    0L
            );
        }

        long startedAt = System.currentTimeMillis();
        boolean ok = aiServiceClient.ping();
        long latencyMs = System.currentTimeMillis() - startedAt;

        if (ok) {
            return service("FastAPI AI 服务", "fastapi", UP, "AI 服务地址：" + baseUrl, latencyMs);
        }

        return service("FastAPI AI 服务", "fastapi", DOWN, "无法连接 AI 服务，地址：" + baseUrl, latencyMs);
    }

    /**
     * MySQL：SELECT 1 实测延迟
     */
    private SystemHealthVO.ServiceHealth mysqlHealth() {
        long startedAt = System.currentTimeMillis();
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            long latencyMs = System.currentTimeMillis() - startedAt;

            if (result != null && result == 1) {
                return service("MySQL 8.0", "mysql", UP, "数据库连接正常", latencyMs);
            }
            return service("MySQL 8.0", "mysql", DOWN, "数据库探测返回异常结果", latencyMs);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            log.warn("MySQL 健康检查失败：{}", e.getMessage());
            return service("MySQL 8.0", "mysql", DOWN, "数据库连接失败：" + truncate(e.getMessage()), latencyMs);
        }
    }

    /**
     * 文件存储：在根目录创建临时文件后删除，验证可写
     */
    private SystemHealthVO.ServiceHealth storageHealth() {
        long startedAt = System.currentTimeMillis();
        Path root = Paths.get(storageProperties.getRoot()).toAbsolutePath().normalize();

        try {
            Files.createDirectories(root);
            Path probe = Files.createTempFile(root, ".health-", ".tmp");
            Files.deleteIfExists(probe);

            long latencyMs = System.currentTimeMillis() - startedAt;
            return service("文件存储", "storage", UP, "本地存储目录可写：" + root, latencyMs);
        } catch (IOException e) {
            long latencyMs = System.currentTimeMillis() - startedAt;
            log.warn("文件存储健康检查失败：{}", e.getMessage());
            return service("文件存储", "storage", DOWN, "存储目录不可写：" + truncate(e.getMessage()), latencyMs);
        }
    }

    /**
     * 由 FastAPI 内部管理、Spring Boot 不直接探测的组件
     */
    private SystemHealthVO.ServiceHealth degradedService(String name, String key, String detail) {
        return service(name, key, DEGRADED, detail, 0L);
    }

    private SystemHealthVO.ServiceHealth service(
            String name,
            String key,
            String status,
            String detail,
            Long latencyMs
    ) {
        SystemHealthVO.ServiceHealth health = new SystemHealthVO.ServiceHealth();
        health.setName(name);
        health.setKey(key);
        health.setStatus(status);
        health.setDetail(detail);
        health.setLatencyMs(latencyMs);
        return health;
    }

    private String truncate(String message) {
        if (message == null) {
            return "未知错误";
        }
        return message.length() > MAX_DETAIL_LENGTH
                ? message.substring(0, MAX_DETAIL_LENGTH)
                : message;
    }
}
