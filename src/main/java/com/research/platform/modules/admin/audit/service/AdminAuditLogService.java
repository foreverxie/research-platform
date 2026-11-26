package com.research.platform.modules.admin.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.research.platform.common.result.PageResult;
import com.research.platform.modules.admin.audit.entity.AdminAuditLog;
import com.research.platform.modules.admin.audit.mapper.AdminAuditLogMapper;
import com.research.platform.modules.admin.audit.vo.AdminAuditLogVO;
import com.research.platform.modules.user.entity.User;
import com.research.platform.modules.user.mapper.UserMapper;
import com.research.platform.utils.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminAuditLogService {
    private final AdminAuditLogMapper auditLogMapper;
    private final UserMapper userMapper;

    public AdminAuditLogService(AdminAuditLogMapper auditLogMapper, UserMapper userMapper) {
        this.auditLogMapper = auditLogMapper;
        this.userMapper = userMapper;
    }

    public void record(String action, String targetType, String targetId, String details) {
        Long actorId = CurrentUser.getCurrentUserId();
        User actor = actorId == null ? null : userMapper.selectById(actorId);
        AdminAuditLog log = new AdminAuditLog();
        log.setActorUserId(actorId);
        log.setActorUsername(actor == null ? "unknown" : actor.getUsername());
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetails(details);
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    public PageResult<AdminAuditLogVO> page(long pageNo, long pageSize, String keyword, String action) {
        QueryWrapper<AdminAuditLog> query = new QueryWrapper<>();
        if (StringUtils.hasText(action)) {
            query.eq("action", action.trim());
        }
        if (StringUtils.hasText(keyword)) {
            String term = keyword.trim();
            query.and(wrapper -> wrapper.like("actor_username", term)
                    .or().like("target_type", term)
                    .or().like("target_id", term)
                    .or().like("details", term));
        }
        query.orderByDesc("created_at").orderByDesc("id");
        Page<AdminAuditLog> page = auditLogMapper.selectPage(new Page<>(pageNo, pageSize), query);
        List<AdminAuditLogVO> records = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    private AdminAuditLogVO toVO(AdminAuditLog log) {
        AdminAuditLogVO vo = new AdminAuditLogVO();
        vo.setId(log.getId());
        vo.setActorUserId(log.getActorUserId());
        vo.setActorUsername(log.getActorUsername());
        vo.setAction(log.getAction());
        vo.setTargetType(log.getTargetType());
        vo.setTargetId(log.getTargetId());
        vo.setDetails(log.getDetails());
        vo.setCreatedAt(log.getCreatedAt());
        return vo;
    }
}
