package com.research.platform.modules.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.research.platform.common.result.PageResult;
import com.research.platform.modules.user.dto.UserLoginDTO;
import com.research.platform.modules.user.dto.UserPasswordUpdateDTO;
import com.research.platform.modules.user.dto.UserRegisterDTO;
import com.research.platform.modules.user.dto.UserUpdateDTO;
import com.research.platform.modules.user.entity.User;
import com.research.platform.modules.user.vo.UserLoginVO;
import com.research.platform.modules.user.vo.UserVO;
import com.research.platform.modules.user.vo.AdminUserVO;
import jakarta.validation.Valid;

public interface IUserService extends IService<User> {
    /**
     * 用户注册
     */
    UserVO register(UserRegisterDTO dto);

    /**
     * 用户登录
     */
    UserLoginVO login(UserLoginDTO dto);

    /** 管理员分页查询用户 */
    PageResult<AdminUserVO> pageUsers(long pageNo, long pageSize, String keyword, Integer status);

    /** 管理员修改用户状态 */
    UserVO updateUserStatus(Long userId, Integer status);


    /**
     * 获取当前登录用户信息
     */
    UserVO getCurrentUser();

    /**
     * 修改当前登录用户个人资料
     */
    UserVO updateCurrentUser(UserUpdateDTO dto);

    /**
     * 修改当前登录用户的密码
     */
    void updatePassword(UserPasswordUpdateDTO dto);

    /**
     * 用户退出登录
     */
    void logout();
}
