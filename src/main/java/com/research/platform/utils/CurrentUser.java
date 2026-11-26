package com.research.platform.utils;

import com.research.platform.common.exception.BusinessException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class CurrentUser {

    /**
     * 获取当前登录用户ID
     *
     * <p>未登录时抛出 401 业务异常，由全局异常处理器返回统一错误结构。</p>
     */
    public static Long getCurrentUserId() {

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(401, "未登录或登录已过期");
        }

        Object principal = authentication.getPrincipal();

        if (!(principal instanceof Long)) {
            throw new BusinessException(401, "当前用户身份信息异常");
        }

        return (Long) principal;
    }
}
