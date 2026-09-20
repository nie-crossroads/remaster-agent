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
 * 切换隔离实现只改这一个分支，其余代码一行不用改。
 */
@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxConfig.class);

    @Bean
    public SandboxExecutor sandboxExecutor(SandboxProperties properties) {
        if (SandboxProperties.MODE_DOCKER.equals(properties.mode())) {
            DockerSandboxExecutor executor = new DockerSandboxExecutor(properties, properties.maxOutputChars());
            executor.verify();
            log.info("沙箱模式 = docker：容器真隔离（--network none + 只读根fs + 非root + 资源封顶）。"
                    + "部署机需预装 Docker，且镜像须预装 JDK21+Maven、本地仓库须预热。");
            return executor;
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
