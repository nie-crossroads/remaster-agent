package com.remasteragent.tools.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调用边抽取的纯函数单测（基于 AST，不依赖数据库）。
 *
 * <p>验证「方法 → 它调用了谁」的名字级候选抽取：this 调用、类型调用、构造调用、
 * 以及 scope 为变量名时的候选生成。这些都是跨文件依赖图的索引侧输入。</p>
 */
class CallEdgeExtractorTest {

    @Test
    @DisplayName("抽取方法调用：this 调用、类型调用、构造调用")
    void extractsCallees() {
        String src = """
                package com.example;

                public class Order {
                    public void place() {
                        calc();                        // this / 同类内调用
                        PriceService.compute(this);    // 类型调用（候选接收者 PriceService）
                        Invoice inv = new Invoice();   // 构造调用
                    }
                    private void calc() {}
                }
                """;
        Map<String, List<String>> edges = CallEdgeExtractor.extract("src/Order.java", src);
        List<String> place = edges.get("com.example.Order#place");
        assertTrue(place != null, "place 方法应在键里");
        assertTrue(place.contains("com.example.Order#calc"), "this 调用落到本类型: " + place);
        assertTrue(place.contains("PriceService#compute"), "类型调用候选: " + place);
        assertTrue(place.contains("PriceService"), "类型骨架候选: " + place);
        assertTrue(place.contains("Invoice"), "构造类型候选: " + place);
        assertTrue(place.contains("Invoice#<init>"), "构造器候选: " + place);
    }

    @Test
    @DisplayName("方法调用 scope 为变量名时抽为类型候选")
    void variableScopeReceiver() {
        String src = """
                package com.example;

                public class Svc {
                    public void run() {
                        ctx.get();
                    }
                }
                """;
        Map<String, List<String>> edges = CallEdgeExtractor.extract("src/Svc.java", src);
        List<String> run = edges.get("com.example.Svc#run");
        assertTrue(run.contains("ctx#get"), "scope 为变量名 ctx → 候选 ctx#get: " + run);
        assertTrue(run.contains("ctx"), "同时抽类型骨架候选: " + run);
    }

    @Test
    @DisplayName("类块不出现在调用边键里")
    void classChunkNotInKeys() {
        String src = """
                package com.example;

                public class Foo {
                    public void m() {}
                }
                """;
        Map<String, List<String>> edges = CallEdgeExtractor.extract("src/Foo.java", src);
        assertTrue(edges.containsKey("com.example.Foo#m"), "方法块应在键里");
        assertTrue(!edges.containsKey("com.example.Foo"), "类块不应在调用边键里");
    }
}
