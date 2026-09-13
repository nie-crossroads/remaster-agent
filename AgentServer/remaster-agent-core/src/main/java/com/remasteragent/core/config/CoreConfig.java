package com.remasteragent.core.config;

import com.remasteragent.core.queue.QueueProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 编排内核装配。
 *
 * <p>刻意保持极薄：内核的能力（调度器、各节点、存储、队列）都以 {@code @Component} / {@code @Repository}
 * 标注，由组件扫描装配。这里只负责把配置类注册进来。之所以不做成一个大而全的
 * {@code @Bean} 工厂，是因为那样会让「编排逻辑依赖了什么」变得难以一眼看清 ——
 * 而这个项目的卖点恰恰是「编排逻辑清晰、可单测」。
 */
@Configuration
@EnableConfigurationProperties({CoreProperties.class, QueueProperties.class})
public class CoreConfig {
}
