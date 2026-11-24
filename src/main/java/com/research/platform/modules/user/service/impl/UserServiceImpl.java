package com.research.platform.modules.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.modules.user.convert.UserConvert;
import com.research.platform.modules.user.dto.UserLoginDTO;
import com.research.platform.modules.user.dto.UserPasswordUpdateDTO;
import com.research.platform.modules.user.dto.UserRegisterDTO;
import com.research.platform.modules.user.dto.UserUpdateDTO;
import com.research.platform.modules.user.entity.User;
import com.research.platform.modules.user.mapper.UserMapper;
import com.research.platform.modules.user.service.IUserService;
import com.research.platform.modules.user.vo.UserLoginVO;
import com.research.platform.modules.user.vo.UserVO;
import com.research.platform.utils.CurrentUser;
import com.research.platform.utils.JwtUtils;
import com.research.platform.utils.RedisSessionService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final RedisSessionService redisSessionService;

    public UserServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtUtils jwtUtils, RedisSessionService redisSessionService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.redisSessionService = redisSessionService;
    }

    /**
     * 用户注册
     * @param dto
     * @return 用户VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO register(UserRegisterDTO dto) {
        // 1. 规范化用户名
        String username = dto.getUsername().trim();
        Long usernameCount = userMapper.selectCount(new QueryWrapper<User>().eq("username", username));

        // 2. 检查用户名是否存在
        if(usernameCount > 0 ){
            throw new BusinessException(409, "用户名已被使用");
        }

        // 3. 规范化邮箱
        String email = dto.getEmail();
        if(email != null){
            email = email.trim();

            if(email.isEmpty()){
                email = null;
            }
        }
        if(email != null){
            Long emailCount = userMapper.selectCount(new QueryWrapper<User>().eq("email", email));
            if(emailCount > 0){
                throw new BusinessException(409, "邮箱已被使用");
            }
        }

        // 5. 密码哈希
        String encodePassword = passwordEncoder.encode(dto.getPassword());

        // 6. 创建用户实体
        User user = new User();
        user.setUsername(username);
        user.setPassword(encodePassword);
        user.setEmail(email);
        user.setNickname(
                dto.getNickname() == null
                        ? null
                        : dto.getNickname().trim()
        );

        // 7. 设置初始状态
        user.setStatus(1);
        user.setDeleted(0);

        // 8. 写入数据库
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(
                    409,
                    "用户名或邮箱已被使用"
            );
        }

        User savedUser = userMapper.selectById(user.getId());

        // 9. 转换为响应对象
        return UserConvert.toVO(savedUser);
    }

    /**
     * 用户登录
     * @param dto
     * @return 用户登录VO
     */
    @Override
    public UserLoginVO login(UserLoginDTO dto) {
        // 1. 获取用户名
        String username = dto.getUsername().trim();

        // 2. 根据用户名查询用户
        User user = userMapper.selectOne(
                new QueryWrapper<User>()
                        .eq("username", username)
        );

        // 3. 用户不存在，或者密码错误
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(401, "用户名或密码错误");
        }

        // 4. 检查用户账号状态
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(403, "账号已被禁用");
        }

        // 5. 生成 JWT
        String token = jwtUtils.generateToken(
                user.getId(),
                user.getUsername()
        );

        // 解析 JWT，获取 JTI
        String jti = jwtUtils.parseToken(token).getId();
        // 保存当前用户的登录会话到 Redis
        redisSessionService.saveToken(user.getId(), jti, jwtUtils.getExpirationInSeconds());

        // 6. 构建登录响应对象
        UserLoginVO loginVO = new UserLoginVO();

        loginVO.setToken(token);
        loginVO.setTokenType("Bearer");
        loginVO.setExpiresIn(jwtUtils.getExpirationInSeconds());
        loginVO.setUser(UserConvert.toVO(user));

        // 7. 返回登录结果
        return loginVO;

    }

    /**
     * 获取当前登录用户信息
     * @return 用户VO
     */
    @Override
    public UserVO getCurrentUser() {
        // 1. 获取当前登录用户ID
        Long userId = CurrentUser.getCurrentUserId();

        // 2. 根据用户ID查询用户
        User user = userMapper.selectById(userId);

        // 3. 检查用户是否存在
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        // 4. 检查用户状态
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(403, "账号已被禁用");
        }

        // 5. 转换为用户信息VO
        return UserConvert.toVO(user);

    }

    /**
     * 修改当前登录用户个人资料
     * @param dto
     * @return 用户VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateCurrentUser(UserUpdateDTO dto) {

        // 1. 获取当前登录用户ID
        Long userId = CurrentUser.getCurrentUserId();

        // 2. 查询当前用户
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        // 3. 检查账号状态
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(403, "账号已被禁用");
        }

        // 4. 处理邮箱
        String email = dto.getEmail();

        if (email != null) {
            email = email.trim();

            if (email.isEmpty()) {
                email = null;
            }
        }

        // 5. 检查邮箱是否被其他用户使用
        if (email != null) {

            Long emailCount = userMapper.selectCount(
                    new QueryWrapper<User>()
                            .eq("email", email)
                            .ne("id", userId)
            );

            if (emailCount > 0) {
                throw new BusinessException(409, "邮箱已被其他用户使用");
            }
        }

        // 6. 更新用户资料
        user.setNickname(dto.getNickname() == null ? null : dto.getNickname().trim());
        user.setEmail(email);
        user.setAvatar(dto.getAvatar() == null ? null : dto.getAvatar().trim());
        userMapper.updateById(user);

        // 8. 重新查询，获取最新数据库数据
        User updatedUser = userMapper.selectById(userId);

        // 9. 转换为VO并返回
        return UserConvert.toVO(updatedUser);

    }

    /**
     * 修改当前登录用户的密码
     * @param dto
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePassword(UserPasswordUpdateDTO dto) {
        // 1. 获取当前登录用户ID
        Long userId = CurrentUser.getCurrentUserId();

        // 2. 查询当前用户
        User user = userMapper.selectById(userId);

        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        // 3. 检查账号状态
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(403, "账号已被禁用");
        }

        // 4. 验证旧密码
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            throw new BusinessException(400, "原密码错误");
        }

        // 5. 检查新密码与确认密码是否一致
        if (!dto.getNewPassword().equals(dto.getConfirmPassword())) {
            throw new BusinessException(400, "两次输入的新密码不一致");
        }

        // 6. 检查新密码不能与旧密码相同
        if (passwordEncoder.matches(dto.getNewPassword(), user.getPassword())) {
            throw new BusinessException(400, "新密码不能与原密码相同");
        }

        // 7. 对新密码进行 BCrypt 加密
        String encodedPassword = passwordEncoder.encode(dto.getNewPassword());

        // 8. 更新数据库
        user.setPassword(encodedPassword);
        userMapper.updateById(user);
        // 9. 删除 Redis 中的登录会话，使当前 Token 失效
        redisSessionService.deleteToken(userId);
    }

    /**
     * 用户退出登录
     */
    @Override
    public void logout() {
        // 获取当前登录用户ID
        Long userId = CurrentUser.getCurrentUserId();

        // 删除 Redis 中的登录会话
        redisSessionService.deleteToken(userId);
    }


}
