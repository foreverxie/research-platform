package com.research.platform.modules.user.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class UserVO {

    /**
     * 用户ID
     */
    private Long id;

    /**
     * 用户名
     */
    private String username;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 头像地址
     */
    private String avatar;

    /**
     * 个人简介
     */
    private String bio;

    /**
     * 用户状态：1正常，0禁用
     */
    private Integer status;

    /**
     * 角色编码列表，例如 ADMIN、USER；前端据此展示角色并控制入口
     */
    private List<String> roles;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
