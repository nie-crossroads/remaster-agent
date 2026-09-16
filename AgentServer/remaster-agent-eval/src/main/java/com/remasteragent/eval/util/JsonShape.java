package com.remasteragent.eval.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * API 载荷的形状守卫 —— 解析前先确认「我依赖的字段确实在」。
 *
 * <p>存在的理由很具体：宽松反序列化（{@link EvalJson}）让「API 多加字段」变得无害，
 * 但也让<b>字段改名</b>退化成「静默的 0」。那种失败最难查 —— 报告里所有指标都变成 0，
 * 而程序一声不吭，跑的人只会以为「Agent 这次表现真差」。
 *
 * <p>所以在进入反序列化之前，先按路径把必需的键点一遍，缺一个就立刻抛，并把
 * 实际存在的键列出来 —— 报错信息要能直接指出「API 现在给的叫什么」。
 */
public final class JsonShape {

    private JsonShape() {
    }

    /**
     * 校验指定路径上的对象存在且含有全部必需键。
     *
     * @param root     根节点
     * @param path     形如 {@code "metrics"} 的点分路径；空串表示根本身
     * @param required 必需键
     * @throws IllegalStateException 路径不存在或缺少键
     */
    public static void requireKeys(JsonNode root, String path, String... required) {
        JsonNode node = path.isEmpty() ? root : root.path(path);
        if (node.isMissingNode() || node.isNull()) {
            throw new IllegalStateException("载荷里找不到节点 '" + path + "'，实际顶层键: " + keysOf(root));
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String key : required) {
            if (!node.has(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "节点 '" + path + "' 缺少必需字段 " + missing + "，实际字段: " + keysOf(node));
        }
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
