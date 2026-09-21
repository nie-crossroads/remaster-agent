package com.remasteragent.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Optional;

/**
 * 演示模式配置。
 *
 * <p>演示模式 = 游客身份运行服务器本地可信样本：不引鉴权（除非显式开启 demo 登录）、不上传通道、
 * 不需要 Docker 沙箱（样本可信）。详见 docs/DEMO_LANDING_ARCH_PLAN.md。
 *
 * <p>样本路径由服务端从配置拼 {@code CreateTaskRequest}，<b>绝不信任客户端传入的路径</b> ——
 * 演示端点的安全边界正是「客户端无法指定要跑哪个工程」。客户端只能从
 * {@link #samples()} 里挑一个 {@code key}，路径仍由服务端解析。
 *
 * <h2>两个共享账号，不是一个</h2>
 * <p>演示网关上有两个账号，角色不同、用途也不同：
 * <ul>
 *   <li>{@link #username()} / {@link #password()}（角色 {@code DEMO}）——给访客用：能跑样本，
 *       工作台里只看得到演示任务。这是「对外的那一档」。</li>
 *   <li>{@link #rootUsername()} / {@link #rootPassword()}（角色 {@code DEMO} + {@code ROOT}）——给
 *       这台机器的主人用：同样的界面，但任务列表显示全部历史任务（含真实迁移记录）。
 *       root 额外持有 {@code DEMO} 是有意的 —— 它应当能做演示账号能做的一切，只是多一层可见范围。</li>
 * </ul>
 * <p>为什么不做用户表/多租户：这两个账号的差别只有「列表可见范围」一件事，
 * 为它引一张表和一套注册流程，是把演示模式的核心立论（刻意与多租户解耦）拆掉。
 * 角色本身由 Spring Security 的 {@code ROLE_*} 权限承载，前端通过
 * {@code GET /api/auth/me} 拿到，不自己猜。
 *
 * <h2>为什么要多个样本</h2>
 * <p>单个样本只能演示「顺利迁移成功」这一种剧本。演示价值更高的恰恰是另外两种结局：
 * 复杂写法下模型能改到什么程度、以及工程本身不可能通过时护栏会不会在有限轮次内停下。
 * 所以样本是一个<b>列表</b>：每个样本自带 key（前端下拉的值）、展示名、工程根与入口文件。
 */
@ConfigurationProperties(prefix = "remaster.demo")
public record DemoProperties(
        /** 是否开启演示端点。false 时 /api/demo/** 返回 404，落地页 CTA 也自动隐藏。 */
        Boolean enabled,
        /** 演示样本列表（一个样本 = 一个可直接运行的本地可信工程）。空列表时 /api/demo/samples 返回空数组。 */
        List<Sample> samples,
        /** 同时进行的演示任务上限（mvn test 很重，避免一个面试官的跑批饿死其他人）。默认 2。 */
        Integer concurrency,
        /** 演示登录共享账号用户名。仅用于演示网关，不建用户表/多租户。默认 demo。 */
        String username,
        /** 演示登录共享密码。生产部署务必通过环境变量覆盖，不要用默认值。默认 remaster-demo。 */
        String password,
        /** 全权限账号用户名。默认 root。 */
        String rootUsername,
        /** 全权限账号密码。生产部署务必通过环境变量覆盖。默认 remaster-root。 */
        String rootPassword
) {
    public DemoProperties {
        enabled = enabled != null && enabled;
        // 防御性拷贝：List.copyOf 既避免外部改动配置，又顺带把 null 元素挡在外面（会直接 NPE，比下游静默出错好）
        samples = samples == null ? List.of() : List.copyOf(samples);
        concurrency = (concurrency == null || concurrency <= 0) ? 2 : concurrency;
        username = (username == null || username.isBlank()) ? "demo" : username;
        password = (password == null || password.isBlank()) ? "remaster-demo" : password;
        rootUsername = (rootUsername == null || rootUsername.isBlank()) ? "root" : rootUsername;
        rootPassword = (rootPassword == null || rootPassword.isBlank()) ? "remaster-root" : rootPassword;
    }

    /**
     * 单个演示样本。
     *
     * @param key         前端下拉的值，也是 /api/demo/run 的入参（客户端唯一能控制的东西）
     * @param name        展示名，落到任务名上（列表/详情里一眼能认出跑的是哪个示例）
     * @param projectRoot 工程根目录绝对路径，必须含 pom.xml
     * @param entryFile   入口文件，相对 projectRoot；null/空 = 整仓升级
     */
    public record Sample(String key, String name, String projectRoot, String entryFile) {
    }

    /**
     * 按 key 取样本；key 为空时取第一个（默认样本）。
     *
     * <p>找不到就返回空 Optional，由调用方决定是 400 还是 404 —— 配置类不该替 HTTP 层选状态码。
     */
    public Optional<Sample> sampleByKey(String key) {
        if (samples.isEmpty()) {
            return Optional.empty();
        }
        if (key == null || key.isBlank()) {
            return Optional.of(samples.get(0));
        }
        return samples.stream().filter(s -> key.trim().equals(s.key())).findFirst();
    }
}
