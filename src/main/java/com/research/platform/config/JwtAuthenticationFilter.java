
package com.research.platform.config;

import com.research.platform.modules.user.entity.User;
import com.research.platform.modules.user.mapper.UserMapper;
import com.research.platform.utils.JwtUtils;
import com.research.platform.utils.RedisSessionService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserMapper userMapper;
    private final RedisSessionService redisSessionService;

    public JwtAuthenticationFilter(
            JwtUtils jwtUtils,
            UserMapper userMapper,
            RedisSessionService redisSessionService) {
        this.jwtUtils = jwtUtils;
        this.userMapper = userMapper;
        this.redisSessionService = redisSessionService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. 获取 Authorization 请求头
        String authorization = request.getHeader("Authorization");

        // 2. 判断是否携带 Bearer Token
        if (authorization != null && authorization.startsWith("Bearer ")) {

            String token = authorization.substring(7);

            try {
                // 3. 验证 Token 签名和有效期
                if (jwtUtils.validateToken(token)) {

                    // 4. 解析 Token
                    Claims claims = jwtUtils.parseToken(token);

                    Long userId = claims.get("userId", Number.class).longValue();
                    String username = claims.getSubject();

                    // 5. 查询数据库，确认用户仍然有效
                    User user = userMapper.selectById(userId);

                    // 获取 JWT 中的 JTI
                    String jti = claims.getId();

                    // 校验 Redis 中的 JTI 是否与当前 Token 一致
                    boolean tokenValid = redisSessionService.isTokenValid(userId, jti);

                    if (user != null
                            && user.getStatus() != null
                            && user.getStatus() == 1
                            && username.equals(user.getUsername())
                            && tokenValid) {

                        // 6. 当前请求尚未认证时，建立认证对象
                        if (SecurityContextHolder.getContext()
                                .getAuthentication() == null) {

                            UsernamePasswordAuthenticationToken authentication =
                                    new UsernamePasswordAuthenticationToken(
                                            userId,
                                            null,
                                            Collections.emptyList()
                                    );

                            authentication.setDetails(
                                    new WebAuthenticationDetailsSource()
                                            .buildDetails(request)
                            );

                            // 7. 保存认证信息
                            SecurityContextHolder.getContext()
                                    .setAuthentication(authentication);
                        }
                    }
                }

            } catch (JwtException | IllegalArgumentException e) {
                // Token 无效时清除认证信息
                SecurityContextHolder.clearContext();
            } catch (RuntimeException e) {
                // 数据库或 Redis 等基础设施异常，清除认证信息
                SecurityContextHolder.clearContext();

                // 继续抛出异常，交由 Spring 的异常处理机制处理
                throw e;
            }
        }

        // 8. 继续执行后续过滤器
        filterChain.doFilter(request, response);
    }
}
