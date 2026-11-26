package com.research.platform.modules.admin.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("admin_audit_log")
public class AdminAuditLog {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("actor_user_id")
    private Long actorUserId;
    @TableField("actor_username")
    private String actorUsername;
    @TableField("action")
    private String action;
    @TableField("target_type")
    private String targetType;
    @TableField("target_id")
    private String targetId;
    @TableField("details")
    private String details;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
