package com.research.platform.config;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStartupChecker {

    private final StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void checkRedisConnection() {
        log.info("正在检查 Redis 连接...");
        try {
            String pong = stringRedisTemplate.execute(RedisConnection::ping);
            log.info("✅ Redis 连接成功, PING -> {}", pong);
        } catch (Exception e) {
            log.error("❌ Redis 连接失败！请确保 Redis 服务已启动。", e);
            // 如果你确实想让应用启动失败，保留下面这行；否则删掉，只打日志
            throw new IllegalStateException("无法连接到 Redis 服务器，请检查配置和 Redis 服务状态", e);
        }
    }
}