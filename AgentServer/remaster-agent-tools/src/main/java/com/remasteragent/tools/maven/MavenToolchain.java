package com.remasteragent.tools.maven;

import com.remasteragent.tools.config.DotEnv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本机 Maven 工具链的位置与调用方式。
 *
 * <p>为什么要把这件事单独抽出来：沙箱执行 {@code mvn test} 时，<b>不能依赖环境变量</b>。
 * 父进程（Worker）可能是被 IDE 或脚本以任意环境启动的，PATH 里未必有 mvn，
 * 有也可能是另一个版本。所以这里把三样东西显式固定下来，每次执行都完整传参：
 *
 * <ul>
 *   <li><b>mvn 可执行文件</b> —— 必须用 {@code mvn.cmd} 的绝对路径。注意：在 Git Bash 里
 *       直接执行 {@code mvn}（那个 shell 脚本）会因为 Windows 路径翻译问题抛
 *       {@code ClassNotFoundException}，而 {@code mvn.cmd} 不受影响。</li>
 *   <li><b>settings.xml</b> —— 决定本地仓库位置与镜像源，必须显式传，不能靠默认值。</li>
 *   <li><b>本地仓库</b> —— 显式传 {@code -Dmaven.repo.local}，保证沙箱与主构建用同一个仓库，
 *       也保证离线执行（{@code -o}）时能找到预热的依赖。</li>
 * </ul>
 *
 * @param executable       mvn.cmd 的绝对路径
 * @param settingsXml      settings.xml 的绝对路径
 * @param localRepository  本地仓库绝对路径
 */
public record MavenToolchain(Path executable, Path settingsXml, Path localRepository) {

    /** 逻辑命令里代表 Maven 的那个词，本地实现会把它替换成真实可执行文件路径。 */
    public static final String LOGICAL_MVN = "mvn";

    /**
     * 从配置解析工具链位置。支持系统属性 / 环境变量 / .env 三级覆盖，
     * 键名分别为 {@code MAVEN_HOME}、{@code MAVEN_SETTINGS}、{@code MAVEN_LOCAL_REPO}。
     *
     * <p>刻意不给默认值：猜错路径导致的失败（比如跑成了另一个 Maven、或在错误的仓库里找依赖）
     * 远比「缺少配置项」这条报错难排查。
     */
    public static MavenToolchain resolve(Map<String, String> env) {
        String home = require(env, "MAVEN_HOME");
        String settings = DotEnv.get(env, "MAVEN_SETTINGS",
                Paths.get(home, "conf", "settings.xml").toString());
        String localRepo = DotEnv.get(env, "MAVEN_LOCAL_REPO");
        if (localRepo == null || localRepo.isBlank()) {
            throw new IllegalStateException(
                    "缺少配置项 MAVEN_LOCAL_REPO —— 沙箱必须显式指定本地仓库，否则离线执行会找不到依赖");
        }
        return new MavenToolchain(
                resolveExecutable(home),
                Paths.get(settings).toAbsolutePath().normalize(),
                Paths.get(localRepo).toAbsolutePath().normalize());
    }

    /**
     * 定位可执行文件。Windows 上优先 {@code mvn.cmd}，其次 {@code mvn.bat}；
     * 类 Unix 上用 {@code mvn}。
     */
    private static Path resolveExecutable(String mavenHome) {
        Path bin = Paths.get(mavenHome, "bin");
        for (String candidate : List.of("mvn.cmd", "mvn.bat", "mvn")) {
            Path path = bin.resolve(candidate);
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("在 " + bin + " 下找不到 mvn 可执行文件");
    }

    private static String require(Map<String, String> env, String key) {
        String value = DotEnv.get(env, key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少配置项 " + key + "（见 .env.example）");
        }
        return value;
    }

    /**
     * 把逻辑命令补齐成本机可直接执行的完整命令。
     *
     * <p>逻辑命令的第一个元素若是 {@code mvn}，会被替换成真实路径，
     * 并自动补上 {@code -s} 与 {@code -Dmaven.repo.local}。
     * 其它命令（如 {@code java}、{@code docker}）原样透传。
     */
    public List<String> decorate(List<String> logicalCommand) {
        if (logicalCommand.isEmpty() || !LOGICAL_MVN.equals(logicalCommand.get(0))) {
            return List.copyOf(logicalCommand);
        }
        List<String> result = new ArrayList<>();
        result.add(executable.toString());
        result.add("-B");
        result.add("-s");
        result.add(settingsXml.toString());
        result.add("-Dmaven.repo.local=" + localRepository);
        result.addAll(logicalCommand.subList(1, logicalCommand.size()));
        return result;
    }

    /** 校验工具链真的存在，用于启动期快速失败而不是等到第一次执行沙箱才报错。 */
    public void verify() {
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("mvn 可执行文件不存在: " + executable);
        }
        if (!Files.isRegularFile(settingsXml)) {
            throw new IllegalStateException("settings.xml 不存在: " + settingsXml);
        }
        if (!Files.isDirectory(localRepository)) {
            throw new IllegalStateException("本地仓库目录不存在: " + localRepository);
        }
    }
}
