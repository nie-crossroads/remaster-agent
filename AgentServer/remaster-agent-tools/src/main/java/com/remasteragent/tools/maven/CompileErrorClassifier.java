package com.remasteragent.tools.maven;

import com.remasteragent.tools.pom.JakartaArtifactCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编译错误<b>确定性分类器</b>：把 {@link MavenResultParser#extractCompileErrors} 抠出来的错误块，
 * 按「为什么错」归到几个可控类别，并附<b>定向提示</b>，让 REWRITE 节点下一轮改得准，而不是瞎猜。
 *
 * <h2>为什么用确定性规则，而不是再调一次 LLM</h2>
 * <p>分类本身又是一次调用、又是一笔成本，而且 LLM 分类不一定比规则准。javac 的报错文本高度结构化，
 * 几条正则 + 一张白名单就能覆盖最常见的几类迁移坑 —— 这正是「Java 工程壁垒兜住 LLM 不确定性」的延续：
 * 把「模型为什么又改错了」这类可机械判断的事，交给规则而不是模型。</p>
 *
 * <h2>类别与各自指向</h2>
 * <ul>
 *   <li>{@link #JAKARTA_LEFTOVER}：javax→jakarta 漏改。复用 {@link JakartaArtifactCatalog} 的白名单
 *       判断（仍在 JDK 里的 {@code javax.crypto}/{@code javax.sql} 等不会误判），提示模型改命名空间；</li>
 *   <li>{@link #API_SIGNATURE}：API 签名/已移除类变更（Spring 6 / Java 21 最常见），提示查新版本写法；</li>
 *   <li>{@link #DEPENDENCY_UNRESOLVED}：依赖未解析/缺失（如 httpclient5 没加、Jakarta 依赖没补齐），
 *       提示先查 pom 而非盲改代码；</li>
 *   <li>{@link #TEST_SOURCE}：错误在测试源集（{@code src/test}），附<b>反作弊</b>提示
 *       —— 不要删测试/禁用测试/降断言来骗过 VERIFY；</li>
 *   <li>{@link #UNCLASSIFIED}：兜底，只给通用修正提示。</li>
 * </ul>
 *
 * <p>本类是纯函数、无 LLM / DB / Maven 依赖，可被确定性单测完全覆盖。它的输入是
 * {@code extractCompileErrors} 已经去重、相对化、按行数截断后的错误块，不涉及任何解析细节。</p>
 */
public final class CompileErrorClassifier {

    /** 编译错误类别。 */
    public enum Category {
        /** javax→jakarta 迁移漏改。 */
        JAKARTA_LEFTOVER,
        /** API 签名/已移除类变更。 */
        API_SIGNATURE,
        /** 依赖未解析/缺失。 */
        DEPENDENCY_UNRESOLVED,
        /** 错误位于测试源集（src/test）。 */
        TEST_SOURCE,
        /** 无法归类，兜底。 */
        UNCLASSIFIED
    }

    /** 单条已分类错误：原始块 + 类别 + 定向提示。 */
    public record ClassifiedError(String block, Category category, String hint) {
    }

    /** 一次分类的总结果：逐条 + 各类别计数（用于可观测日志）。 */
    public record CompileErrorReport(
            List<ClassifiedError> errors,
            int jakartaLeftover,
            int apiSignature,
            int dependencyUnresolved,
            int testSource,
            int unclassified) {

        public static CompileErrorReport empty() {
            return new CompileErrorReport(List.of(), 0, 0, 0, 0, 0);
        }

        public int total() {
            return errors.size();
        }
    }

    /** 错误块首行里的工程相对路径形态（Windows 两种斜杠都认）。 */
    private static final Pattern JAVAX_TOKEN = Pattern.compile("javax\\.[A-Za-z_][A-Za-z0-9_.]*");

    private CompileErrorClassifier() {
    }

    /**
     * 对一批错误块做分类。
     *
     * @param errorBlocks {@link MavenResultParser#extractCompileErrors} 的产出（已去重/相对化/截断）
     * @return 分类结果（与输入顺序一致；空输入返回 {@link CompileErrorReport#empty()}）
     */
    public static CompileErrorReport classify(List<String> errorBlocks) {
        if (errorBlocks == null || errorBlocks.isEmpty()) {
            return CompileErrorReport.empty();
        }

        List<ClassifiedError> errors = new ArrayList<>(errorBlocks.size());
        int jakarta = 0, api = 0, dep = 0, test = 0, unclassified = 0;

        for (String block : errorBlocks) {
            ClassifiedError e = classifyOne(block);
            errors.add(e);
            switch (e.category()) {
                case JAKARTA_LEFTOVER -> jakarta++;
                case API_SIGNATURE -> api++;
                case DEPENDENCY_UNRESOLVED -> dep++;
                case TEST_SOURCE -> test++;
                case UNCLASSIFIED -> unclassified++;
            }
        }
        return new CompileErrorReport(errors, jakarta, api, dep, test, unclassified);
    }

    private static ClassifiedError classifyOne(String block) {
        boolean inTest = isTestSource(block);
        boolean jakarta = isJakartaLeftover(block);
        boolean api = isApiSignature(block);
        boolean dependency = isDependencyUnresolved(block);

        Category contentCategory;
        if (jakarta) {
            contentCategory = Category.JAKARTA_LEFTOVER;
        } else if (api) {
            contentCategory = Category.API_SIGNATURE;
        } else if (dependency) {
            contentCategory = Category.DEPENDENCY_UNRESOLVED;
        } else {
            contentCategory = Category.UNCLASSIFIED;
        }

        // 测试源集：当内容无法归类时，单独归到 TEST_SOURCE（让反作弊提示生效）；
        // 内容已能归类（如测试里也漏改了 jakarta）则保留内容类别，额外追加反作弊提示。
        Category reported = inTest && contentCategory == Category.UNCLASSIFIED
                ? Category.TEST_SOURCE : contentCategory;

        String hint = hintFor(contentCategory)
                + (inTest ? TEST_SOURCE_WARNING : "");
        return new ClassifiedError(block, reported, hint);
    }

    // ---- 各类别的判定 ----

    /**
     * javax→jakarta 漏改：块里出现任意一个命中白名单的 {@code javax.*} 包（坐标）。
     * 用 {@link JakartaArtifactCatalog#forImport} 复用同一张白名单 —— 仍在 JDK 内的
     * {@code javax.crypto}/{@code javax.sql} 等会被它排除，不会误判。
     */
    private static boolean isJakartaLeftover(String block) {
        if (block == null) {
            return false;
        }
        Matcher m = JAVAX_TOKEN.matcher(block);
        while (m.find()) {
            if (JakartaArtifactCatalog.forImport(m.group()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** API 签名/已移除类变更：javac 的「找不到符号」、Spring 类引用、或「已被移除/不抽象」等典型表述。 */
    private static boolean isApiSignature(String block) {
        if (block == null) {
            return false;
        }
        return block.contains("cannot find symbol")
                || block.contains("找不到符号")
                || block.contains("has been removed")
                || block.contains("is not abstract")
                || block.contains("incompatible types")
                || block.contains("org.springframework");
    }

    /** 依赖未解析/缺失：包不存在、import 无法解析、依赖解析失败、读 jar 失败。 */
    private static boolean isDependencyUnresolved(String block) {
        if (block == null) {
            return false;
        }
        return block.contains("does not exist")
                || block.contains("不存在")
                || block.contains("cannot be resolved")
                || block.contains("无法解析")
                || block.contains("could not resolve")
                || block.contains("unresolved dependency")
                || block.contains("error reading");
    }

    /** 错误位于测试源集：块首行路径落在 {@code src/test} 下（相对化后统一用正斜杠）。 */
    private static boolean isTestSource(String block) {
        if (block == null) {
            return false;
        }
        return block.contains("src/test/") || block.contains("src\\test\\");
    }

    // ---- 提示与标签 ----

    private static final String TEST_SOURCE_WARNING =
            " 【此错误位于测试源集 src/test：不要通过删除/禁用测试（@Disabled）或降低断言来通过验证，"
                    + "应修改被测主代码或修正测试本身使其与迁移后的主代码一致】";

    private static String hintFor(Category category) {
        return switch (category) {
            case JAKARTA_LEFTOVER -> "这是 javax→jakarta 迁移漏改：将对应 import 从 javax.* 改为 jakarta.*"
                    + "（如 javax.persistence.* → jakarta.persistence.*、javax.annotation.Resource → "
                    + "jakarta.annotation.Resource、javax.validation → jakarta.validation）。";
            case API_SIGNATURE -> "这是 API 签名/已移除类变更（常见于 Spring 6 / Java 21）：检查该符号"
                    + "在新版本中的正确写法（参考 Spring Boot 3 迁移指南），不要凭旧记忆猜测方法名/类名。";
            case DEPENDENCY_UNRESOLVED -> "这看起来是依赖未解析/缺失，可能并非代码错误：确认 pom 依赖坐标"
                    + "是否随 Spring Boot 3 升级（如 Apache HttpClient 5、Jakarta EE API 依赖是否补齐），"
                    + "优先排查依赖而非盲目改代码。";
            case TEST_SOURCE -> "此错误位于测试源集（src/test）：不要通过删除/禁用测试（@Disabled）或"
                    + "降低断言来通过验证，应修改被测主代码或修正测试本身。";
            case UNCLASSIFIED -> "请根据上面的错误定位与「符号/位置」信息修正代码。";
        };
    }

    /** 给模型看的简短类别标签。 */
    public static String categoryLabel(Category category) {
        return switch (category) {
            case JAKARTA_LEFTOVER -> "jakarta漏改";
            case API_SIGNATURE -> "API签名变更";
            case DEPENDENCY_UNRESOLVED -> "依赖未解析";
            case TEST_SOURCE -> "测试源集";
            case UNCLASSIFIED -> "未归类";
        };
    }
}
