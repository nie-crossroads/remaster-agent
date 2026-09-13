package com.remasteragent.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API 进程入口。
 *
 * <h2>为什么扫描根包是 {@code com.remasteragent} 而不是 {@code com.remasteragent.web}</h2>
 * <p>编排内核、LLM 接入、工具层分处不同模块（不同 jar），它们的 {@code @Component} /
 * {@code @Configuration} 都在各自的包下。若只扫 {@code com.remasteragent.web}，
 * 调度器、节点、存储实现、沙箱装配一个都不会被装配 —— 而这个失败不是编译错误，
 * 是启动时「找不到 Bean」或者更糟的「跑起来但什么都没做」。
 *
 * <h2>这个进程做什么、不做什么</h2>
 * <p>只做 HTTP：创建任务、查状态、推 SSE。它<b>不执行迁移</b> ——
 * 迁移由 Worker 进程跑（开发期可以用 {@code remaster.worker.embedded=true}
 * 把它拉进本进程，见 {@code EmbeddedWorkerConfig}）。
 * 这条边界让长任务不会占住 Web 线程，也让 Worker 可以独立扩容。
 */
@SpringBootApplication(scanBasePackages = "com.remasteragent")
public class WebApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
