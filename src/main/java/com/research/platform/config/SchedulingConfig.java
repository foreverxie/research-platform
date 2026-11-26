package com.research.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关（孤儿文件清理等）
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
