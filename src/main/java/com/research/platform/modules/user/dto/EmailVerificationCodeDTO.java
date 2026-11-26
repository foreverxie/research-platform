package com.research.platform.modules.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class EmailVerificationCodeDTO {
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Pattern(regexp = "(?i)^[^@\\s]+@qq\\.com$", message = "请输入以@qq.com结尾的邮箱")
    @Size(max = 100, message = "邮箱长度不能超过100个字符")
    private String email;
}
