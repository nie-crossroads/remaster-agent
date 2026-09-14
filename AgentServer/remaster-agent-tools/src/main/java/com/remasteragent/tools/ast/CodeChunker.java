package com.remasteragent.tools.ast;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.remasteragent.common.rag.CodeChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * AST 感知的代码切块 —— 代码 RAG 的索引单元生产者。
 *
 * <h2>为什么不用固定窗口切（如「每 40 行一段」）</h2>
 * <p>固定窗口会把一个方法从中间劈开，检索到的半截代码既读不懂也拼不进 prompt；
 * 更糟的是窗口边界与语义边界无关，会让「同一个方法的不同片段」在向量空间里彼此远离。
 * 按语法单元切分，块与「人能理解的最小完整单位」对齐，召回质量完全不同 ——
 * 这正是「用 Java 工程壁垒兜住 LLM 不确定性」在检索层的体现。
 *
 * <h2>块的粒度：类骨架 + 方法，刻意<b>不</b>切字段</h2>
 * <ul>
 *   <li><b>类骨架块</b>（{@link CodeChunk#KIND_CLASS}）：类型签名 + 字段声明，<b>不含方法体</b>。
 *       它回答「这个类是什么、有哪些状态」。</li>
 *   <li><b>方法块</b>（{@link CodeChunk#KIND_METHOD}）：每个方法/构造器一整块，回答「它怎么做事」。</li>
 * </ul>
 * <p>两者内容<b>不重叠</b>：类骨架剔除了方法体，方法块只含方法自己。若让类块包含整个类原文，
 * 就会与每个方法块重复 —— 检索命中时同一段代码被塞进 prompt 两遍，既费 token 又稀释信号。
 * <p>字段不单独成块：一个类里字段通常几十行、单独成块太碎且语义弱，把它们并入类骨架恰好。
 */
public final class CodeChunker {

    private CodeChunker() {
    }

    /**
     * 把一份源码切成若干块。
     *
     * @param relativePath 相对工程根的路径（写进块的 filePath）
     * @param source       文件内容
     * @return 按源码顺序排列的块；文件无类型声明时返回空列表
     * @throws IllegalStateException 源码无法解析时（与 ANALYZE 的前提校验一致：坏源码不该进入索引）
     */
    public static List<CodeChunk> chunk(String relativePath, String source) {
        CompilationUnit unit = JavaSourceAnalyzer.parseOrThrow(source);
        String packageName = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");

        List<CodeChunk> chunks = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.getTypes()) {
            String qualified = packageName.isEmpty()
                    ? type.getNameAsString()
                    : packageName + "." + type.getNameAsString();
            collectType(relativePath, qualified, type, chunks);
        }
        return chunks;
    }

    private static void collectType(String filePath, String qualifiedName,
                                    TypeDeclaration<?> type, List<CodeChunk> out) {
        LineRange typeRange = rangeOf(type);
        out.add(new CodeChunk(filePath, qualifiedName, CodeChunk.KIND_CLASS,
                typeRange.start(), typeRange.end(), classSkeleton(type)));

        // 按 members 的声明顺序遍历，而不是「先 getMethods() 再 getConstructors()」——
        // 后者会把构造器排到所有方法之后，块的顺序与源码顺序脱节。上下文组装时
        // 块顺序就是阅读顺序，读起来像「类长什么样，然后按源码先后逐个方法」才自然。
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof MethodDeclaration method) {
                LineRange range = rangeOf(method);
                out.add(new CodeChunk(filePath, qualifiedName + "#" + method.getNameAsString(),
                        CodeChunk.KIND_METHOD, range.start(), range.end(), method.toString()));
            } else if (member instanceof ConstructorDeclaration constructor) {
                LineRange range = rangeOf(constructor);
                // 构造器统一记作 <init>：JVM 层面它就叫这个名字，符号检索时也好统一匹配
                out.add(new CodeChunk(filePath, qualifiedName + "#<init>",
                        CodeChunk.KIND_METHOD, range.start(), range.end(), constructor.toString()));
            } else if (member instanceof TypeDeclaration<?> nested) {
                // 嵌套类型单独成块，符号用 $ 连接（与 JVM 内部命名一致，便于按内部类检索）
                collectType(filePath, qualifiedName + "$" + nested.getNameAsString(), nested, out);
            }
        }
    }

    /**
     * 类骨架 —— 类型声明 + 字段，刻意不含方法体。
     *
     * <p>类型头自己拼（修饰符 + 类型关键字 + 名字 + 类型参数），因为 JavaParser 并没有一个
     * 通用于所有 {@code TypeDeclaration} 的 {@code getDeclarationAsString}。这里不展开
     * extends/implements —— 继承关系属于依赖图的职责，放进骨架只会让每块都变长、稀释检索信号。
     */
    private static String classSkeleton(TypeDeclaration<?> type) {
        StringBuilder sb = new StringBuilder();
        sb.append(typeHeader(type)).append(" {\n");
        for (FieldDeclaration field : type.getFields()) {
            // 字段声明可能跨多行，缩进对齐后拼进去，保证骨架看起来仍像一段可读的 Java
            sb.append("    ")
                    .append(field.toString().replace("\n", "\n    ").stripTrailing())
                    .append('\n');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String typeHeader(TypeDeclaration<?> type) {
        StringBuilder sb = new StringBuilder();
        for (Modifier modifier : type.getModifiers()) {
            sb.append(modifier.getKeyword().asString()).append(' ');
        }
        // 只取「修饰符 + 类型关键字 + 名字」。泛型参数、extends/implements 都不展开 ——
        // 它们要么是依赖图的职责，要么对「这块是什么」的判定没有增量，放进骨架只会拉长每块
        sb.append(typeKeyword(type)).append(' ').append(type.getNameAsString());
        return sb.toString();
    }

    private static String typeKeyword(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration classOrInterface) {
            return classOrInterface.isInterface() ? "interface" : "class";
        }
        if (type instanceof EnumDeclaration) {
            return "enum";
        }
        if (type instanceof RecordDeclaration) {
            return "record";
        }
        if (type instanceof AnnotationDeclaration) {
            return "@interface";
        }
        return "class";
    }

    /** 取节点在源码中的行号范围（1-based，含两端）。拿不到时返回 (0,0)。 */
    private static LineRange rangeOf(Node node) {
        return node.getRange()
                .map(range -> new LineRange(range.begin.line, range.end.line))
                .orElse(new LineRange(0, 0));
    }

    private record LineRange(int start, int end) {
    }
}
