package com.research.platform.modules.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserPasswordUpdateDTO {

    /**
     * 当前密码
     */
    @NotBlank(message = "当前密码不能为空")
    private String oldPassword;

    /**
     * 新密码
     */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "新密码长度必须为8到64个字符")
    private String newPassword;

    /**
     * 确认新密码
     */
    @NotBlank(message = "确认新密码不能为空")
    private String confirmPassword;
}