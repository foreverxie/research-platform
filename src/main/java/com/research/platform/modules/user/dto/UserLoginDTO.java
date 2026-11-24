
package com.research.platform.modules.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserLoginDTO {

    /**
     * 用户名
     */
    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名长度不能超过50个字符")
    private String username;

    /**
     * 登录密码
     */
    @NotBlank(message = "密码不能为空")
    @Size(max = 64, message = "密码长度不能超过64个字符")
    private String password;

    //TODO 暂时不支持邮箱登录
}
