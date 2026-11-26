package com.research.platform.config;

import com.research.platform.infrastructure.ai.AiProperties;
import com.research.platform.infrastructure.storage.StorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 自定义配置属性注册
 */
@Configuration
@EnableConfigurationProperties({StorageProperties.class, AiProperties.class})
public class PropertiesConfig {
}
