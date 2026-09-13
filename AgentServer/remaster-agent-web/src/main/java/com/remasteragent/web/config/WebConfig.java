package com.remasteragent.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层装配：跨域 + 定时任务。
 *
 * <p>开发期前端跑在 Vite 的 5173 端口，与 API 的 8080 不同源，浏览器会拦掉请求。
 * 两种解法 —— Vite 里配 proxy，或者服务端开 CORS。这里两个都留了：
 * 服务端开 CORS 是为了「前端不在本机」的场景（比如用手机连同一局域网看效果），
 * 这比改前端配置更通用。
 *
 * <p>允许的来源通过 {@code remaster.web.allowed-origins} 覆盖，默认只放开发端口。
 * <b>刻意不用通配符 {@code *}</b>：这个服务能读写本机文件系统上的工程目录，
 * 对任何网站开放跨域等于把本机文件操作暴露给任意页面。
 *
 * <p>{@code @EnableScheduling} 是给 SSE 心跳用的（见 {@code SseEventHub}）：
 * 一次沙箱构建可能几十秒没有事件，中间的空闲连接会被反向代理掐断。
 */
@Configuration
@EnableScheduling
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebConfig(@Value("${remaster.web.allowed-origins:"
            + "http://localhost:5173,http://127.0.0.1:5173}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
