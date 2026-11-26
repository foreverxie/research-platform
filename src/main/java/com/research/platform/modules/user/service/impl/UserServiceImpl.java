package com.research.platform.modules.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.research.platform.common.result.PageResult;
import com.research.platform.common.exception.BusinessException;
import com.research.platform.modules.role.entity.Role;
import com.research.platform.modules.role.entity.UserRole;
import com.research.platform.modules.role.mapper.RoleMapper;
import com.research.platform.modules.role.mapper.UserRoleMapper;
import com.research.platform.modules.user.convert.UserConvert;
import com.research.platform.modules.user.dto.UserLoginDTO;
import com.research.platform.modules.user.dto.UserPasswordUpdateDTO;
import com.research.platform.modules.user.dto.UserRegisterDTO;
import com.research.platform.modules.user.dto.UserUpdateDTO;
import com.research.platform.modules.user.entity.User;
import com.research.platform.modules.user.mapper.UserMapper;
import com.research.platform.modules.user.service.IUserService;
import com.research.platform.modules.user.service.EmailVerificationService;
import com.research.platform.modules.user.vo.UserLoginVO;
import com.research.platform.modules.user.vo.AdminUserVO;
import com.research.platform.modules.user.vo.UserVO;
import com.research.platform.utils.CurrentUser;
import com.research.platform.utils.JwtUtils;
import com.research.platform.utils.RedisSessionService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    /**
     * 新注册用户默认角色
     */
    private static final String DEFAULT_ROLE_CODE = "USER";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final RedisSessionService redisSessionService;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final EmailVerificationService emailVerificationService;

    public UserServiceImpl(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            JwtUtils jwtUtils,
            RedisSessionService redisSessionService,
            RoleMapper roleMapper,
            UserRoleMapper userRoleMapper,
            EmailVerificationService emailVerificationService
    ) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.redisSessionService = redisSessionService;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.emailVerificationService = emailVerificationService;
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
        String email = dto.getEmail().trim().toLowerCase();
        emailVerificationService.verify(email, dto.getEmailCode());
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
        emailVerificationService.consume(email);

        // 9. 分配默认角色
        assignDefaultRole(savedUser.getId());

        // 10. 转换为响应对象
        return toVO(savedUser);
    }

    /**
     * 用户登录
     * @param dto
     * @return 用户登录VO
     */
    @Override
    public UserLoginVO login(UserLoginDTO dto) {
        // 1. 规范化登录标识，兼容用户名登录并支持邮箱登录
        String username = dto.getUsername() == null ? null : dto.getUsername().trim();
        String email = dto.getEmail() == null ? null : dto.getEmail().trim();
        boolean hasUsername = username != null && !username.isEmpty();
        boolean hasEmail = email != null && !email.isEmpty();

        if (hasUsername == hasEmail) {
            throw new BusinessException(400, "请输入用户名或邮箱登录");
        }

        // 2. 根据所选登录标识查询用户
        User user = userMapper.selectOne(
                new QueryWrapper<User>().eq(hasUsername ? "username" : "email", hasUsername ? username : email)
        );

        // 3. 用户不存在，或者密码错误
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(401, "用户名/邮箱或密码错误");
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
        loginVO.setUser(toVO(user));

        // 7. 返回登录结果
        return loginVO;

    }

    @Override
    public PageResult<AdminUserVO> pageUsers(long pageNo, long pageSize, String keyword, Integer status) {
        QueryWrapper<User> query = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String normalizedKeyword = keyword.trim();
            query.and(wrapper -> wrapper
                    .like("username", normalizedKeyword)
                    .or().like("email", normalizedKeyword)
                    .or().like("nickname", normalizedKeyword));
        }
        if (status != null) {
            query.eq("status", status);
        }
        query.orderByDesc("created_at").orderByDesc("id");

        Page<User> page = userMapper.selectPage(new Page<>(pageNo, pageSize), query);
        List<Long> userIds = page.getRecords().stream().map(User::getId).toList();
        var onlineStatus = redisSessionService.getOnlineStatus(userIds);
        List<AdminUserVO> records = page.getRecords().stream().map(user -> {
            AdminUserVO vo = new AdminUserVO();
            UserVO userVO = toVO(user);
            vo.setId(userVO.getId());
            vo.setUsername(userVO.getUsername());
            vo.setEmail(userVO.getEmail());
            vo.setNickname(userVO.getNickname());
            vo.setAvatar(userVO.getAvatar());
            vo.setStatus(userVO.getStatus());
            vo.setRoles(userVO.getRoles());
            vo.setCreatedAt(userVO.getCreatedAt());
            vo.setUpdatedAt(userVO.getUpdatedAt());
            vo.setOnline(onlineStatus.getOrDefault(user.getId(), false));
            return vo;
        }).toList();
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateUserStatus(Long userId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(400, "账号状态只能为0或1");
        }
        if (userId.equals(CurrentUser.getCurrentUserId())) {
            throw new BusinessException(400, "不能修改自己的账号状态");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        user.setStatus(status);
        userMapper.updateById(user);
        if (status == 0) {
            redisSessionService.deleteToken(userId);
        }
        return toVO(userMapper.selectById(userId));
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
        return toVO(user);

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

        // 6. 更新用户资料：仅更新请求中出现的字段，避免未传字段被清空
        if (dto.getNickname() != null) {
            String nickname = dto.getNickname().trim();
            user.setNickname(nickname.isEmpty() ? null : nickname);
        }
        if (dto.getEmail() != null) {
            user.setEmail(email);
        }
        if (dto.getAvatar() != null) {
            String avatar = dto.getAvatar().trim();
            user.setAvatar(avatar.isEmpty() ? null : avatar);
        }
        if (dto.getBio() != null) {
            String bio = dto.getBio().trim();
            user.setBio(bio.isEmpty() ? null : bio);
        }
        userMapper.updateById(user);

        // 8. 重新查询，获取最新数据库数据
        User updatedUser = userMapper.selectById(userId);

        // 9. 转换为VO并返回
        return toVO(updatedUser);

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

    /**
     * 转换为包含角色编码的 VO
     */
    private UserVO toVO(User user) {
        UserVO vo = UserConvert.toVO(user);

        if (vo != null) {
            vo.setRoles(loadRoleCodes(user.getId()));
        }

        return vo;
    }

    /**
     * 查询用户的角色编码
     */
    private List<String> loadRoleCodes(Long userId) {
        List<UserRole> relations = userRoleMapper.selectList(
                new QueryWrapper<UserRole>().eq("user_id", userId)
        );

        if (relations.isEmpty()) {
            return List.of();
        }

        List<Long> roleIds = relations.stream()
                .map(UserRole::getRoleId)
                .toList();

        return roleMapper.selectBatchIds(roleIds).stream()
                .map(Role::getRoleCode)
                .toList();
    }

    /**
     * 为新用户分配默认角色 USER
     */
    private void assignDefaultRole(Long userId) {
        Role role = roleMapper.selectOne(
                new QueryWrapper<Role>().eq("role_code", DEFAULT_ROLE_CODE)
        );

        if (role == null) {
            return;
        }

        Long count = userRoleMapper.selectCount(
                new QueryWrapper<UserRole>()
                        .eq("user_id", userId)
                        .eq("role_id", role.getId())
        );

        if (count != null && count > 0) {
            return;
        }

        UserRole relation = new UserRole();
        relation.setUserId(userId);
        relation.setRoleId(role.getId());
        userRoleMapper.insert(relation);
    }

}
