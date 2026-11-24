
package com.research.platform.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.UUID;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtUtils {

    private final SecretKey secretKey;

    private final long expiration;

    public JwtUtils(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long expiration
    ) {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.expiration = expiration;
    }

    /**
     * 生成 JWT
     *
     * @param userId 用户ID
     * @param username 用户名
     * @return JWT字符串
     */
    public String generateToken(Long userId, String username) {

        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expiration);

        // 生成唯一的 JWT ID
        String jti = UUID.randomUUID().toString();

        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .id(jti)
                .issuedAt(now)
                .expiration(expireDate)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析 JWT
     *
     * @param token JWT字符串
     * @return Claims
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 验证 JWT 是否有效
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 获取用户名
     */
    public String getUsernameFromToken(String token) {
        return parseToken(token).getSubject();
    }

    /**
     * 获取用户ID
     */
    public Long getUserIdFromToken(String token) {
        Number userId = parseToken(token).get("userId", Number.class);
        return userId.longValue();
    }

    /**
     * 获取有效期（秒）
     */
    public long getExpirationInSeconds() {
        return expiration / 1000;
    }
}
