package com.research.platform.modules.system.service;

import com.research.platform.modules.system.vo.SystemHealthVO;

/**
 * 系统状态服务
 */
public interface ISystemService {

    /**
     * 检查各依赖服务的可用性
     */
    SystemHealthVO health();
}
