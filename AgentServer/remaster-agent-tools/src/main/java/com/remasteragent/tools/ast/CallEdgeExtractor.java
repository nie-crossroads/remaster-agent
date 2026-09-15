package com.remasteragent.tools.ast;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 抽「方法 → 它调用了谁（候选）」的名字级依赖边。
 *
 * <p>这是跨文件依赖图的务实版：不引入 symbol-solver 做重载级解析（成本高、边际收益低），
 * 而是从方法调用表达式里抽「接收者类型名 + 方法名」的<b>名字级</b>候选，存进索引，
 * 检索时按符号后缀匹配跨文件命中同名类型。重载 / 继承的精确解析留给后续升级，
 * 但「看懂一段代码需要看它的调用目标」这个最常见的理解断点已经覆盖。</p>
 *
 * <p>候选符号形如 {@code OrderService#getStatus}（方法）与 {@code OrderService}（类型骨架），
 * 它们都是<b>简单名</b>。检索时靠后缀匹配落到全限定符号上 —— 与精确符号解析不同，
 * 同名类型可能多带进来，但 neighbor 扩展只是补充上下文，噪声可控。</p>
 */
public final class CallEdgeExtractor {

    private CallEdgeExtractor() {
    }

    /**
     * 抽一个源文件里每个方法块调用了哪些目标（名字级候选）。
     *
     * @return 键为方法块符号（{@code com.foo.Foo#method} / {@code com.foo.Foo#&lt;init&gt;}），
     *         值为被调用目标候选符号列表；类块不出现在键里
     */
    public static Map<String, List<String>> extract(String relativePath, String source) {
        CompilationUnit unit = JavaSourceAnalyzer.parseOrThrow(source);
        String packageName = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (TypeDeclaration<?> type : unit.getTypes()) {
            String qualified = packageName.isEmpty()
                    ? type.getNameAsString()
                    : packageName + "." + type.getNameAsString();
            collectTypeCalls(type, qualified, edges);
        }
        return edges;
    }

    private static void collectTypeCalls(TypeDeclaration<?> type, String qualified, Map<String, List<String>> out) {
        for (MethodDeclaration method : type.getMethods()) {
            out.put(qualified + "#" + method.getNameAsString(), callsWithin(method, qualified));
        }
        for (ConstructorDeclaration ctor : type.getConstructors()) {
            out.put(qualified + "#<init>", callsWithin(ctor, qualified));
        }
        for (TypeDeclaration<?> nested : type.findAll(TypeDeclaration.class)) {
            if (nested != type) {
                collectTypeCalls(nested, qualified + "$" + nested.getNameAsString(), out);
            }
        }
    }

    private static List<String> callsWithin(CallableDeclaration<?> decl, String ownerQualified) {
        Set<String> candidates = new LinkedHashSet<>();
        decl.findAll(MethodCallExpr.class).forEach(call -> {
            String name = call.getNameAsString();
            String receiver = receiverTypeOf(call.getScope().orElse(null));
            if (receiver != null) {
                candidates.add(receiver + "#" + name);
                candidates.add(receiver);
            } else {
                // this / 同类内调用：落到本类型的全限定符号（与索引里的 symbol 一致）
                candidates.add(ownerQualified + "#" + name);
            }
        });
        decl.findAll(ObjectCreationExpr.class).forEach(creation -> {
            String typeName = creation.getType().getNameAsString();
            candidates.add(typeName);
            candidates.add(typeName + "#<init>");
        });
        return new ArrayList<>(candidates);
    }

    /** 从调用表达式的 scope 里推断「接收者类型名」候选；推断不出返回 null（视为本类内调用）。 */
    private static String receiverTypeOf(Expression scope) {
        if (scope == null) {
            return null;
        }
        if (scope instanceof NameExpr nameExpr) {
            return nameExpr.getNameAsString();
        }
        if (scope instanceof FieldAccessExpr field) {
            // a.b.c() → 最外层作用域的名字（a）作为接收者类型候选
            return receiverTypeOf(field.getScope());
        }
        return null;
    }
}
