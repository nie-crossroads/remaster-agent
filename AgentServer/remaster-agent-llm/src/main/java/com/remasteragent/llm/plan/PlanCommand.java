package com.remasteragent.llm.plan;

import com.remasteragent.llm.retry.LlmAttemptListener;

import java.util.List;

/**
 * 规划请求 —— 把「工程里有哪些文件」交给模型去决定迁移范围与顺序。
 *
 * <p>刻意只给<b>文件清单与符号</b>，不给整份源码：规划阶段判断的是「哪些文件需要改」，
 * 这个决策靠的是类名/方法名/文件名这些结构信息，不是逐行代码。把整库源码塞进 prompt
 * 会让 token 成本爆炸，而规划质量并不会因此提升 —— 真正需要看代码细节的是 REWRITE 阶段。
 *
 * @param entryFile  用户指定的入口文件（相对工程根）
 * @param targetJdk  目标 JDK 版本
 * @param files      工程内的 Java 文件清单（已按路径排序、截断）
 * @param attemptListener 每次 HTTP 尝试失败后的回调，可为空。
 *                    <b>规划也需要它</b>：规划虽然比改写快（实测 12~28s），但走的是同一个网关，
 *                    同样会撞上游 524。少了这个回调，规划的重试又是一段「页面静止但没人知道为什么」。
 */
public record PlanCommand(
        String entryFile,
        int targetJdk,
        List<FileSummary> files,
        LlmAttemptListener attemptListener
) {

    public PlanCommand {
        files = files == null ? List.of() : List.copyOf(files);
        attemptListener = attemptListener == null ? LlmAttemptListener.NONE : attemptListener;
    }

    /** 兼容「不带重试回调」的构造：重试与进度上报交由上层负责。 */
    public PlanCommand(String entryFile, int targetJdk, List<FileSummary> files) {
        this(entryFile, targetJdk, files, null);
    }

    /**
     * 单个文件的摘要。
     *
     * @param filePath    相对工程根路径
     * @param primaryType 主类型名；解析不出时为空串
     * @param symbols     符号清单（方法/字段名），用于让模型判断这个文件是否有遗留写法
     */
    public record FileSummary(
            String filePath,
            String primaryType,
            List<String> symbols
    ) {
    }
}
