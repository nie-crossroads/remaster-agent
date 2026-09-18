package com.remasteragent.tools.ast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 全工程 JDK 移除 API 静态扫描器。
 *
 * <h2>它解决的是评测集掩盖不了的「真实项目痛点」</h2>
 * <p>遗留代码现代化最容易被低估的一步，不是 {@code Date→java.time} 这种教科书改写，
 * 而是 <b>JDK 11 起从 JDK 里删除的一批 EE / CORBA 包</b>：{@code javax.xml.ws}、
 * {@code javax.annotation}（含 {@code @Resource}）、{@code javax.xml.bind}、
 * {@code javax.activation}、{@code javax.servlet}、{@code org.omg.*}（CORBA）等。
 * 这些 import 在 JDK 8 下合法，升到 21 直接编译失败，而且<b>会让整个工程编不过</b>——
 * 哪怕你只改了一个无关文件。评测样例文件太「单纯」，几乎不碰这些，所以测不出来；
 * 但真实业务文件（如 {@code @Resource} 注入、CXF/AXIS 客户端、老 SOAP handler）一抓一大把。</p>
 *
 * <p>本扫描器在<b>规划之前</b>就把整库的这类风险点挖出来：
 * <ol>
 *   <li>把它作为「风险信号」喂给规划器，让它知道哪些文件<b>必须</b>纳入迁移；</li>
 *   <li>作为<b>确定性安全网</b>——模型漏挑的风险文件，由它强制补进迁移清单
 *       （这正是「Java 工程壁垒兜住 LLM 不确定性」的体现：模型可能漏，规则不能漏）。</li>
 * </ol>
 *
 * <h2>为什么只列「确曾从 JDK 移除」的包</h2>
 * <p>清单<b>刻意精确</b>，避免误伤仍在 JDK 里的 API：
 * <ul>
 *   <li>{@code javax.xml.parsers}/{@code javax.xml.transform}/{@code javax.xml.xpath}/
 *       {@code javax.xml.stream}/{@code org.w3c.dom}/{@code org.xml.sax}（JAXP）仍在 JDK，<b>不列</b>；</li>
 *   <li>{@code javax.script}（ScriptEngine）在 JDK 21 仍在，<b>不列</b>；</li>
 *   <li>{@code javax.annotation.processing}/{@code javax.annotation.meta}（注解处理器）仍在 JDK，<b>排除</b>；</li>
 *   <li>{@code javax.sql}/{@code javax.naming}/{@code javax.crypto}/{@code javax.net} 等仍在 JDK，<b>不列</b>。</li>
 * </ul>
 * 列进来的都是「JDK 11 移除、不再随 JDK 提供」的包，命中即编译错误。
 */
public final class JdkRemovalScanner {

    /** 扫描文件数上限，防止超大工程把内存/时间吃爆。 */
    private static final int MAX_FILES = 4000;

    /**
     * 从 JDK 移除的包 → 移除版本 + 推荐替代（jakarta 命名空间为主）。
     * key 为包前缀：import 等于 key 或以 {@code key + "."} 开头即命中。
     */
    private static final Map<String, RemovalInfo> REMOVED_PACKAGES = Map.ofEntries(
            Map.entry("javax.xml.ws", new RemovalInfo(11, "jakarta.xml.ws", "JAX-WS 已从 JDK 移除")),
            Map.entry("javax.xml.ws.soap", new RemovalInfo(11, "jakarta.xml.ws.soap", "JAX-WS SOAP 已从 JDK 移除")),
            Map.entry("javax.xml.bind", new RemovalInfo(11, "jakarta.xml.bind", "JAXB 已从 JDK 移除")),
            Map.entry("javax.xml.soap", new RemovalInfo(11, "jakarta.xml.soap", "SAAJ 已从 JDK 移除")),
            Map.entry("javax.xml.rpc", new RemovalInfo(11, null, "JAX-RPC 已从 JDK 移除")),
            Map.entry("javax.jws", new RemovalInfo(11, "jakarta.jws", "JWS 已从 JDK 移除")),
            Map.entry("javax.activation", new RemovalInfo(11, "jakarta.activation", "Activation 已从 JDK 移除")),
            Map.entry("javax.annotation", new RemovalInfo(11, "jakarta.annotation", "@Resource/@PostConstruct 等已从 JDK 移除")),
            Map.entry("javax.transaction", new RemovalInfo(11, "jakarta.transaction", "JTA 已从 JDK 移除")),
            Map.entry("javax.persistence", new RemovalInfo(11, "jakarta.persistence", "JPA 已从 JDK 移除")),
            Map.entry("javax.servlet", new RemovalInfo(11, "jakarta.servlet", "Servlet API 已从 JDK 移除")),
            Map.entry("javax.ejb", new RemovalInfo(11, "jakarta.ejb", "EJB API 已从 JDK 移除")),
            Map.entry("javax.mail", new RemovalInfo(11, "jakarta.mail", "JavaMail 已从 JDK 移除")),
            Map.entry("javax.management.j2ee", new RemovalInfo(11, null, "J2EE 管理已从 JDK 移除")),
            Map.entry("javax.rmi.CORBA", new RemovalInfo(11, null, "RMI-IIOP/CORBA 已从 JDK 移除")),
            Map.entry("org.omg", new RemovalInfo(11, null, "CORBA 已从 JDK 移除")),
            Map.entry("com.sun.corba", new RemovalInfo(11, null, "CORBA 实现已从 JDK 移除"))
    );

    /** 仍在 JDK 内、不应被 {@code javax.annotation} 规则误伤的包前缀。 */
    private static final List<String> EXCLUDED_PREFIXES = List.of(
            "javax.annotation.processing", "javax.annotation.meta");

    private JdkRemovalScanner() {
    }

    /** 单例入口：扫描器是无状态的，复用同一个实例即可。 */
    public static JdkRemovalScanner create() {
        return new JdkRemovalScanner();
    }

    /**
     * 扫描整个工程目录，返回「相对路径 → 该文件命中的移除风险」映射。
     *
     * <p>解析失败的文件会被跳过（拿不到 import 清单，硬塞进去只会降低质量）。
     * 相对路径用 {@code /} 分隔，与任务里 {@code entryFile} 的写法保持一致，方便后续比对。
     */
    public ProjectRemovalRisks scan(Path workspace) {
        Map<String, List<RemovalRisk>> byFile = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(workspace)) {
            List<Path> javaFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".java"))
                    .limit(MAX_FILES)
                    .toList();
            for (Path file : javaFiles) {
                String relative = workspace.relativize(file).toString().replace('\\', '/');
                try {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    List<RemovalRisk> risks = analyze(source);
                    if (!risks.isEmpty()) {
                        byFile.put(relative, risks);
                    }
                } catch (IOException e) {
                    // 读不到就跳过，不阻断整体扫描
                }
            }
        } catch (IOException e) {
            return new ProjectRemovalRisks(byFile);
        }
        return new ProjectRemovalRisks(byFile);
    }

    /**
     * 分析单个源文件，返回命中的移除风险清单（可能为空）。
     *
     * <p>公开为静态方法，便于单测直接喂入源码片段验证，不必落盘成文件。
     */
    public static List<RemovalRisk> analyze(String source) {
        List<RemovalRisk> risks = new ArrayList<>();
        List<String> imports;
        try {
            imports = JavaSourceAnalyzer.imports(source);
        } catch (IllegalStateException e) {
            return risks; // 解析不了就不报风险，交由护栏/编译去兜底
        }
        for (String imp : imports) {
            RemovalInfo info = match(imp);
            if (info != null) {
                risks.add(new RemovalRisk(imp, info.removedInJdk(), info.replacement(), info.note()));
            }
        }
        return risks;
    }

    private static RemovalInfo match(String importFqn) {
        for (String excluded : EXCLUDED_PREFIXES) {
            if (importFqn.equals(excluded) || importFqn.startsWith(excluded + ".")) {
                return null;
            }
        }
        for (Map.Entry<String, RemovalInfo> entry : REMOVED_PACKAGES.entrySet()) {
            String pkg = entry.getKey();
            if (importFqn.equals(pkg) || importFqn.startsWith(pkg + ".")) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 单个被移除 API 的元信息。 */
    public record RemovalInfo(int removedInJdk, String replacement, String note) {
    }

    /** 一个文件对某个被移除 API 的命中。 */
    public record RemovalRisk(
            String importFqn,
            int removedInJdk,
            String replacement,
            String note
    ) {
        /** 给人/模型看的一行风险标记，形如 {@code javax.annotation.Resource (JDK11移除→jakarta.annotation)}。 */
        public String humanFlag() {
            String tail = replacement != null && !replacement.isEmpty()
                    ? "→" + replacement
                    : "";
            return importFqn + " (JDK" + removedInJdk + "移除" + tail + ")";
        }
    }

    /** 整个工程的命中结果。 */
    public record ProjectRemovalRisks(Map<String, List<RemovalRisk>> byFile) {

        /** 所有命中了「会致编译失败」的移除 API 的文件相对路径。 */
        public List<String> filesWithBlockingRisk() {
            return byFile.entrySet().stream()
                    .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                    .map(Map.Entry::getKey)
                    .toList();
        }

        /** 取某文件的风险人类可读标记；没命中返回空列表。 */
        public List<String> flagsFor(String relativePath) {
            List<RemovalRisk> risks = byFile.get(relativePath);
            if (risks == null || risks.isEmpty()) {
                return List.of();
            }
            List<String> flags = new ArrayList<>(risks.size());
            for (RemovalRisk risk : risks) {
                flags.add(risk.humanFlag());
            }
            return flags;
        }
    }
}
