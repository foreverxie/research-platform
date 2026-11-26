package com.research.platform.modules.system.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系统健康状态视图
 *
 * <p>字段与前端 {@code SystemHealth} 保持一致；不返回任何密钥或内部令牌。</p>
 */
@Data
public class SystemHealthVO {

    private LocalDateTime checkedAt;

    private List<ServiceHealth> services;

    /**
     * 单个依赖服务状态
     */
    @Data
    public static class ServiceHealth {

        private String name;

        /**
         * 固定 key：spring / fastapi / mysql / milvus / mineru / embedding / storage
         */
        private String key;

        /**
         * UP / DOWN / DEGRADED
         */
        private String status;

        private String detail;

        private Long latencyMs;
    }
}
