
package com.research.platform.modules.user.vo;

import lombok.Data;

@Data
public class UserLoginVO {

    /**
     * JWT访问令牌
     */
    private String token;

    /**
     * 令牌类型
     */
    private String tokenType = "Bearer";

    /**
     * 令牌有效期（秒）
     */
    private Long expiresIn;

    /**
     * 用户基本信息
     */
    private UserVO user;
}
