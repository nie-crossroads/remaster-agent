package com.remasteragent.tools.ast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 扫描源码里「Spring Boot 2 → 3（Spring Framework 5 → 6）的破坏性 API」用法。
 *
 * <h2>它与 {@link LegacyImportDetector} 的区别（务必分清，否则会造出新的死锁）</h2>
 * <table>
 *   <caption>两把尺子的口径对比</caption>
 *   <tr><th></th><th>{@link LegacyImportDetector}</th><th>本类</th></tr>
 *   <tr><td>量什么</td><td>命名空间（javax → jakarta）</td><td>框架 API 的破坏性变更</td></tr>
 *   <tr><td>命中意味着</td><td><b>必然</b>编译失败</td><td><b>可能</b>编译失败 / 已过时待删除</td></tr>
 *   <tr><td>能否做「改写后必须为空」的硬校验</td><td>能，且必须做</td><td><b>不能</b></td></tr>
 * </table>
 * <p>为什么本类的命中不能当硬校验：{@code HttpComponentsClientHttpRequestFactory} 在 Spring 6 里<b>仍然存在</b>，
 * 错的只是喂给它的 HttpClient 版本。模型把它保留下来、把底层换成 httpclient5 是<b>正确</b>的改写；
 * 若按「命中即失败」处理，文件改对了也会被判失败，回退配额被白白烧穿。
 * 所以本类只做两件事：<b>挑选值得被改写的候选文件</b>、<b>给出该怎么改的提示</b>。
 *
 * <h2>为什么需要它（博客工程实测暴露的漏洞）</h2>
 * <p>此前「哪些文件该改」只有命名空间一把尺子，于是 {@code RestTemplateConfig.java} 这种
 * 一个 {@code javax} 都没有、却用了 Spring 6 已换底层实现的 {@code HttpComponentsClientHttpRequestFactory}
 * 的文件，既进不了模型的规划清单，也进不了 {@link LegacyImportDetector} 的安全网 ——
 * 结果整仓 VERIFY 卡在它一个文件上，而这个文件从头到尾没人动过。</p>
 *
 * <h2>规则为什么是「文本特征」而不是 AST</h2>
 * <p>这些 API 的判别特征就是「用了哪个类型 / 哪个方法签名」，文本匹配足够且更稳：
 * 不依赖工程能否被 JavaParser 完整解析（遗留工程常解析不了），也不会因为符号解析失败而漏报。
 * 代价是注释里提到同样名字会误报 —— 但本类只用于「多选几个候选文件」，误报的代价仅是一次改写，
 * 漏报的代价是整仓编译失败，权衡下这个方向是对的。</p>
 */
public final class Spring3BreakingApiScanner {

    /** 一条命中。 */
    public record Finding(
            /** 规则标识，稳定可读，供日志与依赖 catalog 引用。 */
            String ruleId,
            /** 命中的特征（类型名 / 方法签名），给人看。 */
            String symbol,
            /** 该怎么改，直接进改写 prompt。 */
            String hint
    ) {
        /** {@code ruleId(symbol)} 形状的一行摘要。 */
        public String flag() {
            return ruleId + "(" + symbol + ")";
        }
    }

    /** 一条规则：{@code allOf} 里的每个正则都命中才算命中（用于「同时含 A 与 B」这类复合特征）。 */
    private record Rule(String id, String symbol, String hint, List<Pattern> allOf) {

        boolean matches(String source) {
            for (Pattern pattern : allOf) {
                if (!pattern.matcher(source).find()) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final List<Rule> RULES = List.of(
            new Rule("SPRING6_HTTPCOMPONENTS_FACTORY", "HttpComponentsClientHttpRequestFactory",
                    "Spring Framework 6 的 HttpComponentsClientHttpRequestFactory 的底层已从 Apache HttpClient 4"
                            + " 换成 HttpClient 5：它只接受 org.apache.hc.client5.http.classic.HttpClient。"
                            + "请把 org.apache.http.impl.client.HttpClients / CloseableHttpClient 的写法"
                            + "换成 org.apache.hc.client5.http.impl.classic.HttpClients / CloseableHttpClient"
                            + "（依赖 org.apache.httpcomponents.client5:httpclient5 已由依赖升级节点注入），"
                            + "或改用 SimpleClientHttpRequestFactory 去掉对 HttpClient 的直接依赖。",
                    List.of(Pattern.compile("\\bHttpComponentsClientHttpRequestFactory\\b"))),
            new Rule("SPRING6_RESPONSE_ERROR_HANDLER", "ResponseErrorHandler.handleError(ClientHttpResponse)",
                    "Spring Framework 6 给 ResponseErrorHandler 增加了 handleError(URI, HttpMethod, ClientHttpResponse)"
                            + " 默认方法，单参数的 handleError(ClientHttpResponse) 已被标记 @Deprecated(forRemoval=true)。"
                            + "请改为实现三参数版本 handleError(URI url, HttpMethod method, ClientHttpResponse response)，"
                            + "并去掉对单参数版本的覆写。",
                    List.of(Pattern.compile("\\bResponseErrorHandler\\b"),
                            Pattern.compile("handleError\\s*\\(\\s*(?:final\\s+)?(?:ClientHttpResponse|org\\.springframework\\.http\\.client\\.ClientHttpResponse)"))),
            new Rule("SPRING6_WEBMVC_CONFIGURER_ADAPTER", "WebMvcConfigurerAdapter",
                    "Spring Boot 3 已删除 WebMvcConfigurerAdapter（Spring Framework 5 起即已过时）。"
                            + "请改为直接 implements WebMvcConfigurer，并按需覆写其中带默认实现的方法。",
                    List.of(Pattern.compile("\\bWebMvcConfigurerAdapter\\b"))),
            new Rule("HTTPCLIENT4_LEGACY", "org.apache.http.*",
                    "源码仍在使用 Apache HttpClient 4（org.apache.http.*）。Spring Boot 3 生态建议迁移到"
                            + " HttpClient 5（org.apache.hc.client5.*）；若它作为参数传给 RestTemplate 的"
                            + " HttpComponentsClientHttpRequestFactory，则不迁移会直接编译失败。",
                    List.of(Pattern.compile("^\\s*import\\s+org\\.apache\\.http\\.[\\w.]+;", Pattern.MULTILINE)))
    );

    private Spring3BreakingApiScanner() {
    }

    /**
     * 扫描单个源文件，返回命中的规则清单（可能为空）。
     *
     * <p>公开为静态方法，便于单测直接喂源码片段，不必落盘。</p>
     */
    public static List<Finding> scan(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        List<Finding> hits = new ArrayList<>();
        for (Rule rule : RULES) {
            if (rule.matches(source)) {
                hits.add(new Finding(rule.id(), rule.symbol(), rule.hint()));
            }
        }
        return List.copyOf(hits);
    }

    /** 是否命中任意一条规则。 */
    public static boolean hasAny(String source) {
        return !scan(source).isEmpty();
    }

    /**
     * 把命中拼成一段可直接进 prompt / 失败反馈的文字。
     *
     * <p>返回空串表示没有命中，调用方据此决定要不要附加这一节。</p>
     */
    public static String describe(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Finding finding : findings) {
            sb.append("- [").append(finding.ruleId()).append("] ").append(finding.hint()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 扫描整个工程目录，返回「相对路径 → 该文件命中的规则」映射（只含命中的文件）。
     *
     * <p>相对路径用 {@code /} 分隔，与 {@code entryFile}、任务里的文件键保持一致。
     * 读不到的文件跳过，不阻断整体扫描。</p>
     */
    public static Map<String, List<Finding>> scanProject(Path workspace) {
        Map<String, List<Finding>> byFile = new LinkedHashMap<>();
        if (workspace == null || !Files.isDirectory(workspace)) {
            return byFile;
        }
        try (var walk = Files.walk(workspace)) {
            List<Path> javaFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".java"))
                    .toList();
            for (Path file : javaFiles) {
                String relative = workspace.relativize(file).toString().replace('\\', '/');
                try {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    List<Finding> hits = scan(source);
                    if (!hits.isEmpty()) {
                        byFile.put(relative, hits);
                    }
                } catch (IOException e) {
                    // 读不到就跳过，不阻断整体扫描
                }
            }
        } catch (IOException e) {
            return byFile;
        }
        return byFile;
    }
}
