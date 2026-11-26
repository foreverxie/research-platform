package com.research.platform.modules.admin.audit.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AdminAuditLogVO {
    private Long id;
    private Long actorUserId;
    private String actorUsername;
    private String action;
    private String targetType;
    private String targetId;
    private String details;
    private LocalDateTime createdAt;
}
