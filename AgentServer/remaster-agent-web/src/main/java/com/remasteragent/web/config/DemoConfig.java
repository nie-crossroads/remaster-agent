package com.remasteragent.web.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 绑定演示模式配置 {@link DemoProperties}。
 *
 * <p>单独一个 @Configuration 只做一件事：让 Spring 把 {@code remaster.demo.*}
 * 绑定到 {@code DemoProperties} 这个 record 上。这样 DemoController 直接注入 DemoProperties 即可，
 * 不用在每个用到配置的地方重复读环境变量。
 */
@Configuration
@EnableConfigurationProperties(DemoProperties.class)
public class DemoConfig {
}
