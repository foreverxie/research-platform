package com.research.platform.modules.dashboard.service;

import com.research.platform.modules.dashboard.vo.DashboardStatsVO;

/**
 * 仪表盘统计服务
 */
public interface IDashboardService {

    /**
     * 当前用户的首页统计数据
     */
    DashboardStatsVO getStats();
}
