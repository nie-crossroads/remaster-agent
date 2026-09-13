package com.remasteragent.tools.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 .env 读取器。
 *
 * <p>Spring Boot 侧用的是 {@code spring.config.import} 把 .env 当 properties 加载；
 * 这里是为**脱离 Spring 运行的一次性工具**（建库引导、环境探针）准备的 ——
 * 它们要在容器都还没起来的时候就能读到配置，不能依赖 Spring 的配置体系。
 *
 * <p>取值优先级：系统属性 &gt; 进程环境变量 &gt; .env 文件 &gt; 调用方给的默认值。
 * 这个顺序保证 CI / 容器里可以用真实环境变量覆盖本地 .env，而不必改文件。
 */
public final class DotEnv {

    private static final String ENV_FILE_NAME = ".env";

    private DotEnv() {
    }

    /**
     * 从指定目录开始向上逐级查找 .env，找到就加载。
     * 找不到返回空表而不是抛异常 —— 因为这时环境变量可能已经提供了全部配置。
     */
    public static Map<String, String> load(Path startDir) {
        Path envFile = findUpwards(startDir);
        if (envFile == null) {
            return Map.of();
        }
        return parse(envFile);
    }

    /** 从当前工作目录开始向上查找。 */
    public static Map<String, String> load() {
        return load(Paths.get("").toAbsolutePath());
    }

    /** 向上查找 .env，最多找 6 层，避免在异常目录结构里无限向上。 */
    public static Path findUpwards(Path startDir) {
        Path dir = startDir;
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(ENV_FILE_NAME);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static Map<String, String> parse(Path envFile) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            for (String rawLine : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                // 允许行尾注释（值里不含 # 的场景足够覆盖本项目）
                int hash = line.indexOf('#');
                if (hash > 0) {
                    line = line.substring(0, hash).trim();
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                // 去掉可能存在的包裹引号
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(key, value);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取 .env 失败: " + envFile, e);
        }
        return result;
    }

    /** 按优先级取值。 */
    public static String get(Map<String, String> env, String key, String defaultValue) {
        String fromProperty = System.getProperty(key);
        if (isNotBlank(fromProperty)) {
            return fromProperty;
        }
        String fromEnv = System.getenv(key);
        if (isNotBlank(fromEnv)) {
            return fromEnv;
        }
        String fromFile = env.get(key);
        if (isNotBlank(fromFile)) {
            return fromFile;
        }
        return defaultValue;
    }

    public static String get(Map<String, String> env, String key) {
        return get(env, key, null);
    }

    public static int getInt(Map<String, String> env, String key, int defaultValue) {
        String value = get(env, key);
        return isNotBlank(value) ? Integer.parseInt(value.trim()) : defaultValue;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
