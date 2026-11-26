package com.research.platform.modules.model.controller;

import com.research.platform.common.result.Result;
import com.research.platform.modules.model.dto.ModelConfigUpdateDTO;
import com.research.platform.modules.model.service.IModelConfigService;
import com.research.platform.modules.model.vo.ModelConfigVO;
import com.research.platform.modules.admin.audit.service.AdminAuditLogService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI 模型配置接口
 *
 * <p>列表对所有登录用户可读（系统设置页展示）；
 * 修改与连通性测试仅系统管理员可用。</p>
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final IModelConfigService modelConfigService;
    private final AdminAuditLogService auditLogService;

    public ModelController(IModelConfigService modelConfigService, AdminAuditLogService auditLogService) {
        this.modelConfigService = modelConfigService;
        this.auditLogService = auditLogService;
    }

    /**
     * 模型配置列表
     */
    @GetMapping
    public Result<List<ModelConfigVO>> listModels() {
        return Result.success(modelConfigService.listConfigs());
    }

    /**
     * 更新模型配置（仅管理员）
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public Result<ModelConfigVO> updateModel(
            @PathVariable Long id,
            @Valid @RequestBody ModelConfigUpdateDTO dto
    ) {
        ModelConfigVO updated = modelConfigService.updateConfig(id, dto);
        auditLogService.record("ADMIN_MODEL_CONFIG_UPDATED", "MODEL_CONFIG", id.toString(),
                "modelName=" + updated.getModelName() + ", status=" + updated.getStatus()
                        + ", isDefault=" + updated.getIsDefault());
        return Result.success("模型配置已保存", updated);
    }

    /**
     * 测试模型连通性（仅管理员）
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> testModel(@PathVariable Long id) {
        Map<String, Object> result = modelConfigService.testConnection(id);
        auditLogService.record("ADMIN_MODEL_CONNECTION_TESTED", "MODEL_CONFIG", id.toString(),
                "ok=" + result.get("ok") + ", latencyMs=" + result.get("latencyMs"));
        return Result.success(result);
    }
}
