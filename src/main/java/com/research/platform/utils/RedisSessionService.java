
package com.research.platform.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RedisSessionService {

    private static final String TOKEN_KEY_PREFIX = "auth:token:user:";

    private final StringRedisTemplate stringRedisTemplate;

    public RedisSessionService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 保存用户当前有效的 JTI
     *
     * @param userId 用户ID
     * @param jti JWT唯一标识
     * @param expirationSeconds Token有效期（秒）
     */
    public void saveToken(Long userId, String jti, long expirationSeconds) {
        String key = buildTokenKey(userId);

        stringRedisTemplate.opsForValue().set(key, jti, Duration.ofSeconds(expirationSeconds)
        );
    }

    /**
     * 获取用户当前有效的 JTI
     */
    public String getToken(Long userId) {
        String key = buildTokenKey(userId);

        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 判断用户当前 Token 是否有效
     */
    public boolean isTokenValid(Long userId, String jti) {
        String currentJti = getToken(userId);

        return currentJti != null && currentJti.equals(jti);
    }

    /**
     * 删除用户当前登录会话
     */
    public void deleteToken(Long userId) {
        String key = buildTokenKey(userId);

        stringRedisTemplate.delete(key);
    }

    /**
     * 构建 Redis Key
     */
    private String buildTokenKey(Long userId) {
        return TOKEN_KEY_PREFIX + userId;
    }
}
