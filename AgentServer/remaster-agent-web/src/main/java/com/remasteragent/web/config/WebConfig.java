package com.remasteragent.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Web 层装配：定时任务。
 *
 * <p>跨域（CORS）已统一交给 {@link SecurityConfig} 的 Spring Security 过滤器链处理
 * （否则预检 OPTIONS 请求会在到达 DispatcherServlet 之前被安全链拦下，跨域头加不上）。
 * 这里不再单独配置，避免两套 CORS 规则叠加产生重复的 {@code Access-Control-Allow-Origin}。
 *
 * <p>{@code @EnableScheduling} 是给 SSE 心跳用的（见 {@code SseEventHub}）：
 * 一次沙箱构建可能几十秒没有事件，中间的空闲连接会被反向代理掐断。
 */
@Configuration
@EnableScheduling
public class WebConfig {
}
