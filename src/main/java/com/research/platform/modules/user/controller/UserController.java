package com.research.platform.modules.user.controller;

import com.research.platform.common.result.Result;
import com.research.platform.common.result.PageResult;
import com.research.platform.modules.user.dto.UserLoginDTO;
import com.research.platform.modules.user.dto.EmailVerificationCodeDTO;
import com.research.platform.modules.user.dto.UserPasswordUpdateDTO;
import com.research.platform.modules.user.dto.UserRegisterDTO;
import com.research.platform.modules.user.dto.UserUpdateDTO;
import com.research.platform.modules.user.dto.UserStatusUpdateDTO;
import com.research.platform.modules.user.service.IUserService;
import com.research.platform.modules.user.service.EmailVerificationService;
import com.research.platform.modules.user.vo.UserLoginVO;
import com.research.platform.modules.user.vo.UserVO;
import com.research.platform.modules.user.vo.AdminUserVO;
import com.research.platform.modules.admin.audit.service.AdminAuditLogService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RequestMapping("/api/users")
@RestController
public class UserController {

    private final IUserService userService;
    private final EmailVerificationService emailVerificationService;
    private final AdminAuditLogService auditLogService;

    public UserController(
            IUserService userService,
            EmailVerificationService emailVerificationService,
            AdminAuditLogService auditLogService
    ) {
        this.userService = userService;
        this.emailVerificationService = emailVerificationService;
        this.auditLogService = auditLogService;
    }

    /** 发送注册邮箱验证码 */
    @PostMapping("/register/email-code")
    public Result<Void> sendRegistrationEmailCode(@Valid @RequestBody EmailVerificationCodeDTO dto) {
        emailVerificationService.sendCode(dto.getEmail());
        return Result.success("验证码已发送", null);
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody UserRegisterDTO userRegisterDTO){
        UserVO userVO = userService.register(userRegisterDTO);
        return Result.success("注册成功",userVO);
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public Result<UserLoginVO> login(@Valid @RequestBody UserLoginDTO userLoginDTO){
        UserLoginVO userLoginVO = userService.login(userLoginDTO);
        return Result.success("登录成功",userLoginVO);
    }

    /** 管理员分页查看用户及其账号状态 */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin")
    public Result<PageResult<AdminUserVO>> pageUsers(
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status
    ) {
        long safePageNo = Math.max(1, pageNo);
        long safePageSize = Math.min(100, Math.max(1, pageSize));
        if (status != null && status != 0 && status != 1) {
            return Result.error(400, "账号状态只能为0或1");
        }
        return Result.success(userService.pageUsers(safePageNo, safePageSize, keyword, status));
    }

    /** 管理员启用或禁用用户；不能禁用当前管理员自己 */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/admin/{userId}/status")
    public Result<UserVO> updateUserStatus(
            @PathVariable Long userId,
            @Valid @RequestBody UserStatusUpdateDTO dto
    ) {
        UserVO updated = userService.updateUserStatus(userId, dto.getStatus());
        auditLogService.record(
                dto.getStatus() == 1 ? "ADMIN_USER_ENABLED" : "ADMIN_USER_DISABLED",
                "USER",
                userId.toString(),
                "username=" + updated.getUsername() + ", status=" + dto.getStatus()
        );
        return Result.success("用户状态已更新", updated);
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/me")
    public Result<UserVO> getCurrentUser() {

        UserVO userVO = userService.getCurrentUser();

        return Result.success("获取用户信息成功", userVO);
    }

    /**
     * 修改当前登录用户的个人资料
     */
    @PutMapping("/me")
    public Result<UserVO> updateCurrentUser(@Valid @RequestBody UserUpdateDTO dto) {

        UserVO userVO = userService.updateCurrentUser(dto);

        return Result.success("用户资料修改成功", userVO);
    }

    /**
     * 修改当前登录用户的密码
     */
    @PutMapping("/me/password")
    public Result<Void> updatePassword(@Valid @RequestBody UserPasswordUpdateDTO dto) {

        userService.updatePassword(dto);

        return Result.success("密码修改成功", null);
    }

    /**
     * 用户退出登录
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        userService.logout();
        return Result.success("退出登录成功", null);
    }

}
