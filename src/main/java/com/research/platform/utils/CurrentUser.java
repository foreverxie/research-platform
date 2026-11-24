
package com.research.platform.utils;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class CurrentUser {

    /**
     * 获取当前登录用户ID
     */
    public static Long getCurrentUserId() {

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new IllegalStateException("当前用户未登录");
        }

        Object principal = authentication.getPrincipal();

        if (!(principal instanceof Long)) {
            throw new IllegalStateException("当前用户身份信息异常");
        }

        return (Long) principal;
    }
}
