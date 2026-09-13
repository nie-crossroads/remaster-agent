package com.remasteragent.tools.sandbox;

import com.remasteragent.tools.maven.MavenToolchain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

/**
 * 沙箱装配。
 *
 * <p>这里体现的是「可替换的隔离实现」怎么落地：上层（VERIFY 节点）只依赖
 * {@link SandboxExecutor} 接口，具体用哪种隔离由配置决定、由这里装配。
 * 装好 Docker 之后，只需要在这里补一个分支，其余代码一行不用改。
 */
@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxConfig.class);

    @Bean
    public SandboxExecutor sandboxExecutor(SandboxProperties properties) {
        if (SandboxProperties.MODE_DOCKER.equals(properties.mode())) {
            throw new IllegalStateException(
                    "Docker 沙箱实现尚未接入。当前可用的只有 local 模式（本机受限子进程）。"
                            + "如果本机还没装 Docker，请把 SANDBOX_MODE 设回 local。");
        }

        MavenToolchain toolchain = new MavenToolchain(
                Paths.get(properties.mavenHome(), "bin", "mvn.cmd").toAbsolutePath().normalize(),
                Paths.get(properties.mavenSettings()).toAbsolutePath().normalize(),
                Paths.get(properties.mavenLocalRepo()).toAbsolutePath().normalize());

        log.warn("沙箱模式 = local：限时限内存，但【没有文件系统与网络隔离】，"
                + "只能对自有可信工程执行。装好 Docker 后请切换到 docker 模式。");

        return new LocalProcessSandboxExecutor(toolchain, properties.maxOutputChars());
    }
}
