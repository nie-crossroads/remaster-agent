package com.remasteragent.tools.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 沙箱配置。
 *
 * <p>把「用哪种隔离」「限多少资源」「Maven 在哪」放一起，是因为它们本质上是同一个决定：
 * 在哪台机器上、以什么代价、执行不可信的代码。分散在多个配置段里最容易出现
 * 「改了超时忘了改内存」这类不一致。
 *
 * @param mode           隔离模式：{@code local}（本机受限子进程）或 {@code docker}（容器真隔离）
 * @param memoryLimitMb  单次执行的内存上限（MB），通过 MAVEN_OPTS 限制 fork 出的 JVM 堆
 * @param timeoutSeconds 单次执行的硬超时（秒），超时后连同子进程树一起强杀
 * @param maxOutputChars 带回的日志上限字符数，超出部分头尾保留、中间省略
 * @param mavenHome      Maven 安装目录
 * @param mavenSettings  settings.xml 路径
 * @param mavenLocalRepo 本地仓库路径
 * @param docker         Docker 沙箱的可选配置（{@code remaster.sandbox.docker.*}），全可空
 */
@ConfigurationProperties(prefix = "remaster.sandbox")
public record SandboxProperties(
        String mode,
        Integer memoryLimitMb,
        Integer timeoutSeconds,
        Integer maxOutputChars,
        String mavenHome,
        String mavenSettings,
        String mavenLocalRepo,
        DockerSandboxProperties docker
) {

    public static final String MODE_LOCAL = "local";
    public static final String MODE_DOCKER = "docker";

    public SandboxProperties {
        mode = (mode == null || mode.isBlank()) ? MODE_LOCAL : mode;
        memoryLimitMb = memoryLimitMb == null ? 1024 : memoryLimitMb;
        timeoutSeconds = timeoutSeconds == null ? 600 : timeoutSeconds;
        maxOutputChars = maxOutputChars == null ? 40_000 : maxOutputChars;
    }

    /** 本项目的沙箱没有真正的隔离，这个属性用于在日志与前端明确提示风险。 */
    public boolean isolated() {
        return MODE_DOCKER.equals(mode);
    }
}
