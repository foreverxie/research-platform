package com.research.platform.modules.user.controller;

import com.research.platform.common.result.Result;
import com.research.platform.modules.user.dto.UserLoginDTO;
import com.research.platform.modules.user.dto.EmailVerificationCodeDTO;
import com.research.platform.modules.user.dto.UserPasswordUpdateDTO;
import com.research.platform.modules.user.dto.UserRegisterDTO;
import com.research.platform.modules.user.dto.UserUpdateDTO;
import com.research.platform.modules.user.service.IUserService;
import com.research.platform.modules.user.service.EmailVerificationService;
import com.research.platform.modules.user.vo.UserLoginVO;
import com.research.platform.modules.user.vo.UserVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api/users")
@RestController
public class UserController {

    private final IUserService userService;
    private final EmailVerificationService emailVerificationService;

    public UserController(IUserService userService, EmailVerificationService emailVerificationService) {
        this.userService = userService;
        this.emailVerificationService = emailVerificationService;
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
