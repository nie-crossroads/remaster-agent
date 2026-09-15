package com.remasteragent.core.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 依赖图多跳扩展的纯函数单测（不依赖数据库）。
 *
 * <p>calleeLookup 用内存 Map 模拟存储层：给定一组全限定符号，返回它们的被调用目标。
 * 这样「图遍历」逻辑与「查库」解耦，能在没有 PG 的情况下钉死去重 / depth 限制 / 自环等行为。</p>
 */
class DependencyExpanderTest {

    private static Set<String> callees(Map<String, Set<String>> g, Set<String> front) {
        Set<String> out = new LinkedHashSet<>();
        front.forEach(s -> out.addAll(g.getOrDefault(s, Set.of())));
        return out;
    }

    private static Function<Set<String>, Set<String>> lookup(Map<String, Set<String>> g) {
        return front -> callees(g, front);
    }

    @Test
    @DisplayName("depth=0：返回空集，且不会触发 callee 查询")
    void depthZeroReturnsEmpty() {
        Map<String, Set<String>> g = Map.of("A", Set.of("B"));
        assertTrue(DependencyExpander.expand(Set.of("A"), 0, lookup(g)).isEmpty());
    }

    @Test
    @DisplayName("一跳：拿到直接被调用目标")
    void oneHop() {
        Map<String, Set<String>> g = Map.of("A", Set.of("B"), "B", Set.of());
        assertEquals(Set.of("B"), DependencyExpander.expand(Set.of("A"), 1, lookup(g)));
    }

    @Test
    @DisplayName("多跳且去重：depth=2 从 A 得 B,C；depth=3 得 B,C,D")
    void multiHopDedup() {
        Map<String, Set<String>> g = Map.of(
                "A", Set.of("B"), "B", Set.of("C"), "C", Set.of("D"), "X", Set.of("A"));
        assertEquals(Set.of("B", "C"), DependencyExpander.expand(Set.of("A"), 2, lookup(g)));
        assertEquals(Set.of("B", "C", "D"), DependencyExpander.expand(Set.of("A"), 3, lookup(g)));
    }

    @Test
    @DisplayName("自环不会无限扩展，也不会把种子算进结果")
    void selfLoopNotRevisited() {
        Map<String, Set<String>> g = Map.of("A", Set.of("A"));
        assertTrue(DependencyExpander.expand(Set.of("A"), 5, lookup(g)).isEmpty());
    }

    @Test
    @DisplayName("多个种子合并扩展，间接目标去重")
    void multipleSeeds() {
        Map<String, Set<String>> g = Map.of("A", Set.of("X"), "B", Set.of("X"), "X", Set.of("Y"));
        assertEquals(Set.of("X", "Y"), DependencyExpander.expand(Set.of("A", "B"), 2, lookup(g)));
    }
}
