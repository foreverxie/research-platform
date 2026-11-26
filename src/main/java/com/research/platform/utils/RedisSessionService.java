
package com.research.platform.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** 批量读取仍有有效登录会话的用户 */
    public Map<Long, Boolean> getOnlineStatus(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<String> keys = userIds.stream().map(this::buildTokenKey).toList();
        List<String> values = stringRedisTemplate.opsForValue().multiGet(keys);
        Map<Long, Boolean> onlineStatus = new HashMap<>();
        if (values == null) {
            return onlineStatus;
        }
        for (int index = 0; index < userIds.size(); index++) {
            onlineStatus.put(userIds.get(index), values.get(index) != null);
        }
        return onlineStatus;
    }

    /**
     * 构建 Redis Key
     */
    private String buildTokenKey(Long userId) {
        return TOKEN_KEY_PREFIX + userId;
    }
}
