package com.research.platform.modules.admin.audit.controller;

import com.research.platform.common.result.PageResult;
import com.research.platform.common.result.Result;
import com.research.platform.modules.admin.audit.service.AdminAuditLogService;
import com.research.platform.modules.admin.audit.vo.AdminAuditLogVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuditLogController {
    private final AdminAuditLogService auditLogService;

    public AdminAuditLogController(AdminAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public Result<PageResult<AdminAuditLogVO>> page(
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String action
    ) {
        return Result.success(auditLogService.page(Math.max(1, pageNo), Math.min(100, Math.max(1, pageSize)), keyword, action));
    }
}
