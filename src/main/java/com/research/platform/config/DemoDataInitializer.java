package com.research.platform.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.research.platform.modules.model.entity.ModelConfig;
import com.research.platform.modules.model.mapper.ModelConfigMapper;
import com.research.platform.modules.role.entity.Role;
import com.research.platform.modules.role.entity.UserRole;
import com.research.platform.modules.role.mapper.RoleMapper;
import com.research.platform.modules.role.mapper.UserRoleMapper;
import com.research.platform.modules.user.entity.User;
import com.research.platform.modules.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 开发环境基础数据初始化
 *
 * <p>只补齐“系统运行必需”的种子数据：角色、角色关联、演示账号、默认模型配置。
 * 不会写入任何文献、解析结果或 AI 分析结果等业务数据，避免把演示内容
 * 与真实分析结果混淆。</p>
 */
@Component
@Profile("dev")
public class DemoDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private static final String DEMO_USERNAME = "demo";
    private static final String DEMO_PASSWORD = "123456";

    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserMapper userMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final PasswordEncoder passwordEncoder;

    public DemoDataInitializer(
            RoleMapper roleMapper,
            UserRoleMapper userRoleMapper,
            UserMapper userMapper,
            ModelConfigMapper modelConfigMapper,
            PasswordEncoder passwordEncoder
    ) {
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.userMapper = userMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(String... args) {
        Long adminRoleId = ensureRole("ADMIN", "系统管理员", "系统管理员，可管理模型配置与系统状态");
        Long userRoleId = ensureRole("USER", "普通用户", "科研人员，管理个人文献库");

        User demo = userMapper.selectOne(new QueryWrapper<User>().eq("username", DEMO_USERNAME));

        if (demo == null) {
            demo = new User();
            demo.setUsername(DEMO_USERNAME);
            demo.setPassword(passwordEncoder.encode(DEMO_PASSWORD));
            demo.setEmail("demo@research.edu.cn");
            demo.setNickname("林知远");
            demo.setStatus(1);
            demo.setDeleted(0);
            userMapper.insert(demo);
            log.info("已创建演示账号：{} / {}", DEMO_USERNAME, DEMO_PASSWORD);
        }

        ensureUserRole(demo.getId(), userRoleId);

        // 已有账号补齐角色：admin 赋予管理员，其余至少为普通用户
        List<User> users = userMapper.selectList(null);

        for (User user : users) {
            if ("admin".equals(user.getUsername())) {
                ensureUserRole(user.getId(), adminRoleId);
            }
            ensureUserRole(user.getId(), userRoleId);
        }

        ensureModelConfigs();
    }

    /**
     * 确保角色存在
     */
    private Long ensureRole(String code, String name, String description) {
        Role role = roleMapper.selectOne(new QueryWrapper<Role>().eq("role_code", code));

        if (role != null) {
            return role.getId();
        }

        role = new Role();
        role.setRoleCode(code);
        role.setRoleName(name);
        role.setDescription(description);
        roleMapper.insert(role);

        log.info("已创建角色：{}", code);
        return role.getId();
    }

    /**
     * 确保用户角色关联存在
     */
    private void ensureUserRole(Long userId, Long roleId) {
        Long count = userRoleMapper.selectCount(
                new QueryWrapper<UserRole>()
                        .eq("user_id", userId)
                        .eq("role_id", roleId)
        );

        if (count != null && count > 0) {
            return;
        }

        UserRole relation = new UserRole();
        relation.setUserId(userId);
        relation.setRoleId(roleId);
        userRoleMapper.insert(relation);
    }

    /**
     * 确保默认模型配置存在（只保存环境变量名，不保存密钥）
     */
    private void ensureModelConfigs() {
        Long count = modelConfigMapper.selectCount(null);

        if (count != null && count > 0) {
            return;
        }

        insertModel("qwen-plus", "LLM", "阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "QWEN_API_KEY", null, 1);
        insertModel("qwen-turbo", "LLM", "阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "QWEN_API_KEY", null, 0);
        insertModel("text-embedding-v3", "EMBEDDING", "阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "QWEN_EMBEDDING_API_KEY", 1024, 1);

        log.info("已初始化默认模型配置");
    }

    private void insertModel(
            String modelName,
            String modelType,
            String provider,
            String endpoint,
            String envName,
            Integer dimension,
            int isDefault
    ) {
        ModelConfig config = new ModelConfig();
        config.setModelName(modelName);
        config.setModelType(modelType);
        config.setProvider(provider);
        config.setApiEndpoint(endpoint);
        config.setApiKeyEnvName(envName);
        config.setDimension(dimension);
        config.setIsDefault(isDefault);
        config.setStatus(1);
        modelConfigMapper.insert(config);
    }
}
