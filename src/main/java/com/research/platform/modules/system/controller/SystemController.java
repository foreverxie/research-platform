package com.research.platform.modules.system.controller;

import com.research.platform.common.result.Result;
import com.research.platform.modules.system.service.ISystemService;
import com.research.platform.modules.system.vo.SystemHealthVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统状态接口
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final ISystemService systemService;

    public SystemController(ISystemService systemService) {
        this.systemService = systemService;
    }

    /**
     * 各依赖服务健康状态
     */
    @GetMapping("/health")
    public Result<SystemHealthVO> health() {
        return Result.success(systemService.health());
    }
}
