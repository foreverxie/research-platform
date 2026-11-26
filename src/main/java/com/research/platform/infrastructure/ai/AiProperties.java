package com.research.platform.infrastructure.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 服务配置（research.ai.*）
 *
 */
@Data
@ConfigurationProperties(prefix = "research.ai")
public class AiProperties {

    /**
     * 是否启用 AI 服务；关闭时解析、AI 分析与问答会返回明确错误，
     * 文献管理、笔记、报告等非 AI 业务不受影响（NFR-003）。
     */
    private boolean enabled = false;

    /**
     * FastAPI 服务地址
     */
    private String baseUrl = "http://localhost:8000";

    /**
     * 服务间调用令牌，来自环境变量
     */
    private String internalToken = "";

    /**
     * 连接超时（秒）
     */
    private int connectTimeoutSeconds = 5;

    /**
     * 读取超时（秒）
     */
    private int readTimeoutSeconds = 60;

}
