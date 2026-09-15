package com.remasteragent.core.rag;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * 依赖图多跳扩展（纯函数，可单测）。
 *
 * <p>给定种子符号集合，按「调用边」做 BFS 扩展：第 1 跳拿到种子的直接被调用目标，
 * 第 depth 跳拿到间接目标。返回<b>扩展后</b>的全部符号（不含种子自身，调用方自行合并）。</p>
 *
 * <p>{@code calleeLookup} 由存储层实现：输入一组全限定符号，返回它们的被调用目标符号集合
 * （也已是全限定，靠名字级候选 → 全限定的后缀匹配完成跨文件落点）。
 * 把「查库」与「图遍历」分开，遍历逻辑就能用内存 Map 单测，不依赖数据库。</p>
 */
public final class DependencyExpander {

    private DependencyExpander() {
    }

    /**
     * @param seeds        种子符号（全限定）
     * @param depth        扩展深度（0 = 不扩展，返回空集）
     * @param calleeLookup 给定符号集合 → 它们的被调用目标集合（全限定）
     * @return 扩展后的符号集合（去重，不含 seeds）
     */
    public static Set<String> expand(Set<String> seeds, int depth,
                                      Function<Set<String>, Set<String>> calleeLookup) {
        Set<String> result = new LinkedHashSet<>();
        Set<String> frontier = new LinkedHashSet<>(seeds);
        for (int hop = 0; hop < depth; hop++) {
            if (frontier.isEmpty()) {
                break;
            }
            Set<String> next = new LinkedHashSet<>(calleeLookup.apply(frontier));
            next.removeAll(seeds);
            next.removeAll(result);
            if (next.isEmpty()) {
                break;
            }
            result.addAll(next);
            frontier = next;
        }
        return result;
    }
}
