package com.remasteragent.tools.sandbox;

/**
 * Docker 沙箱的可选配置。全部可空：为空时由 {@link DockerSandboxExecutor} 取安全默认值。
 *
 * <p>放在 {@code remaster.sandbox.docker} 子段下（如 {@code remaster.sandbox.docker.image}）。
 * 因为网络已被 {@code --network none} 切断，所以<b>仓库与 settings 必须以只读方式挂载进容器</b>，
 * 且命令必须离线（{@code -o}）——这些由执行器保证，不依赖这里的配置。
 *
 * @param image               预装 JDK21 + Maven 的镜像；默认 {@code maven:3.9-eclipse-temurin-21}
 * @param user                容器内运行用户（非 root）；默认 {@code 1000:1000}
 * @param mavenRepo           宿主侧本地仓库，挂载进容器只读；默认取 {@code mavenLocalRepo}
 * @param settings            宿主侧 settings.xml，挂载进容器只读；默认取 {@code mavenSettings}
 * @param pidsLimit           容器内进程数上限（防 fork 炸弹）；默认 256
 * @param cpus                容器可用核数（如 {@code 2}）；默认不限
 * @param containerWorkspace  容器内工作目录挂载点；默认 {@code /workspace}
 * @param containerMavenRepo  容器内本地仓库挂载点；默认 {@code /m2/repository}
 * @param containerSettings   容器内 settings.xml 挂载点；默认 {@code /m2/settings.xml}
 */
public record DockerSandboxProperties(
        String image,
        String user,
        String mavenRepo,
        String settings,
        Integer pidsLimit,
        String cpus,
        String containerWorkspace,
        String containerMavenRepo,
        String containerSettings
) {
}
