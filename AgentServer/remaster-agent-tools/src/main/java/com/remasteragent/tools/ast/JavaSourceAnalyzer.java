package com.remasteragent.tools.ast;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.remasteragent.common.agent.AnalyzeResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Java 源码分析 —— 用 AST 而不是正则去理解代码。
 *
 * <p>这一层是纯 Python / 纯 LLM 背景的候选人做不出来的部分：JavaParser 能在 JVM 里
 * 真正解析语法树，拿到精确的包名、类名、方法签名、字段名与行号范围。
 * 用正则去匹配 {@code class} 关键字做「代码理解」，遇到泛型、嵌套类、
 * 字符串字面量里出现的 class 字样就会立刻失准。
 *
 * <p>它承担两个职责：
 * <ol>
 *   <li><b>组装改写上下文</b>：产出符号清单，让模型知道自己改的是哪个类的哪个方法</li>
 *   <li><b>充当产出校验器</b>：模型返回的代码必须能通过解析，且包名类名不能变
 *       —— 这是 Guardrail 的核心判据</li>
 * </ol>
 */
public final class JavaSourceAnalyzer {

    /**
     * 解析用的语言级别 —— <b>必须显式设置，这一行是踩过坑换来的。</b>
     *
     * <p>JavaParser 的默认语言级别是 {@code JAVA_11}，于是 {@code record}（14+）、
     * <b>文本块（15+）</b>、{@code switch} 表达式（12+）全都会被判为语法错误。
     * 而本项目的改写 prompt 恰恰<i>明确要求</i>模型使用文本块等新语法 ——
     * 结果是：模型每次交回合法且符合预期的产出，护栏都会判「无法解析」并触发重试，
     * 三轮耗尽后任务必然失败。也就是说这个默认值会让整个项目的核心指标恒为 0，
     * 而且错误信息指向的是「模型产出非法」，极难归因。
     *
     * <p>取 21 是因为阶段 1 的迁移目标固定为 JDK 21（见 {@code MigrationTask.DEFAULT_TARGET_JDK}）。
     * 这里刻意<b>偏宽松</b>：护栏只是进入沙箱前的廉价预筛，
     * 真正判断「能不能在 JDK 21 上编译」的是沙箱里的 Maven —— 那才是权威。
     * 预筛过松的代价是白跑一次沙箱，过紧的代价是无谓重试直到任务假失败，后者严重得多。
     *
     * <p>与之配套的两条纪律：
     * <ul>
     *   <li>用独立 {@link JavaParser} 实例，不改 {@code StaticJavaParser} 的全局配置 ——
     *       后者是共享状态，改了会静默影响其它使用者。</li>
     *   <li>每次解析新建实例：{@code JavaParser} 不是线程安全的，
     *       而分析可能被多个 Worker 线程并发调用。它的构造开销相对解析本身可以忽略。</li>
     * </ul>
     */
    private static final LanguageLevel LANGUAGE_LEVEL = LanguageLevel.JAVA_21;

    private static final ParserConfiguration PARSER_CONFIGURATION =
            new ParserConfiguration().setLanguageLevel(LANGUAGE_LEVEL);

    private JavaSourceAnalyzer() {
    }

    /**
     * 源码的头部信息（不含方法体）。
     *
     * @param packageName   包名，默认包时为空字符串
     * @param primaryType   主类型名（首个顶层类/接口/枚举/record）；没有类型时为空字符串
     * @param symbols       符号清单，形如 {@code com.foo.OrderService#getStatus}
     * @param typeCount     顶层类型数量
     */
    public record ParsedHeader(String packageName, String primaryType, List<String> symbols, int typeCount) {

        public static ParsedHeader empty() {
            return new ParsedHeader("", "", List.of(), 0);
        }
    }

    /**
     * 解析源码并抽取头部信息。
     *
     * <p><b>必须检查 {@code isSuccessful()}，不能只看结果是否存在。</b>
     * JavaParser 在解析失败时依然会把「尽力恢复出来的半截 AST」放进结果里
     * （实测：缺一个右括号的类，{@code getResult()} 是有的，只是 problems 里有 2 条）。
     * 若只用 {@code getResult().isEmpty()} 判断，语法坏掉的产出会被判为「可解析」，
     * 护栏最关键的那道闸门就形同虚设，坏代码会一路走到沙箱里，
     * 最后以「编译失败」的形式出现 —— 而真正的原因（产出本身不合法）再也追不回来了。
     *
     * @throws IllegalStateException 语法不合法时抛出，调用方据此判定产出不可信
     */
    public static ParsedHeader parseHeader(String source) {
        ParseResult<CompilationUnit> result;
        try {
            result = new JavaParser(PARSER_CONFIGURATION).parse(source);
        } catch (Exception e) {
            throw new IllegalStateException("源码无法解析: " + e.getMessage(), e);
        }

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            throw new IllegalStateException("源码无法解析: " + describe(result.getProblems()));
        }
        CompilationUnit unit = result.getResult().get();

        String packageName = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");

        List<TypeDeclaration<?>> types = unit.getTypes();
        String primaryType = types.isEmpty() ? "" : types.get(0).getNameAsString();

        List<String> symbols = new ArrayList<>();
        for (TypeDeclaration<?> type : types) {
            String qualifiedType = packageName.isEmpty()
                    ? type.getNameAsString()
                    : packageName + "." + type.getNameAsString();
            symbols.add(qualifiedType);

            for (MethodDeclaration method : type.getMethods()) {
                symbols.add(qualifiedType + "#" + method.getNameAsString() + "()");
            }
            for (FieldDeclaration field : type.getFields()) {
                field.getVariables().forEach(variable ->
                        symbols.add(qualifiedType + "#" + variable.getNameAsString()));
            }
            // 嵌套类也带上，避免检索时漏掉内部类
            for (ClassOrInterfaceDeclaration nested : type.findAll(ClassOrInterfaceDeclaration.class)) {
                if (nested != type) {
                    symbols.add(qualifiedType + "$" + nested.getNameAsString());
                }
            }
        }

        return new ParsedHeader(packageName, primaryType, symbols, types.size());
    }

    /** 把解析问题压成一行可读文本 —— 这条消息会被拼进失败反馈喂回给模型。 */
    private static String describe(List<Problem> problems) {
        if (problems == null || problems.isEmpty()) {
            return "(没有更多信息)";
        }
        return problems.stream()
                .limit(3)
                .map(problem -> {
                    String location = problem.getLocation()
                            .flatMap(range -> range.getBegin().getRange().map(r -> r.begin.line + ":" + r.begin.column))
                            .orElse("?");
                    return "第 " + location + " 行 " + problem.getMessage();
                })
                .collect(Collectors.joining("; "));
    }

    /** 产出能否被解析。Guardrail 的第一步。 */
    public static boolean isParseable(String source) {
        try {
            parseHeader(source);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 包名。解析失败时返回空 Optional。 */
    public static Optional<String> packageName(String source) {
        try {
            return Optional.of(parseHeader(source).packageName());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 主类型名。解析失败时返回空 Optional。 */
    public static Optional<String> primaryType(String source) {
        try {
            String type = parseHeader(source).primaryType();
            return type.isEmpty() ? Optional.empty() : Optional.of(type);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 组装 ANALYZE 节点的产出。
     *
     * <p>阶段 1 直接给整个文件（单文件场景足够），没有做检索。
     * 阶段 2 接入 AST 感知切分与混合检索后，这里会变成「相关片段 + 依赖图邻居」的集合。
     *
     * @param relativePath 相对工程根的路径
     * @param source       文件内容
     */
    public static AnalyzeResult analyze(String relativePath, String source) {
        ParsedHeader header = parseHeader(source);
        String summary = "解析出 %d 个顶层类型，首个为 %s，符号 %d 个".formatted(
                header.typeCount(),
                header.primaryType().isEmpty() ? "(无)" : header.primaryType(),
                header.symbols().size());
        return new AnalyzeResult(
                relativePath,
                header.packageName(),
                header.primaryType(),
                header.symbols(),
                source,
                summary);
    }
}
