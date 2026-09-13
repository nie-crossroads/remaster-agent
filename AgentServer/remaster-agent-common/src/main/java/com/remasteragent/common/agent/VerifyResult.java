package com.remasteragent.common.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * VERIFY 节点的产出 —— 整个闭环里唯一的「真相来源」。
 *
 * <p>模型说自己改对了没有意义，只有这里的结果算数：真的编译过了吗？真的单测全绿吗？
 * 覆盖率有没有掉？编译或测试失败时，{@code failureExcerpt} 会被拼回 prompt 里让模型重写，
 * 因此它必须只保留**关键错误行**（编译错误位置、失败断言的堆栈头部），
 * 整段 Maven 输出喂回去既贵又会稀释重点。
 *
 * <p>这个对象会被序列化进 {@code dag_node.result}（JSONB）当 checkpoint 用，所以它的
 * JSON 形状必须**恰好等于下面 10 个字段**：派生方法（如 {@link #isGreen()}）必须标
 * {@link JsonIgnore}，否则 Jackson 会把它当成额外属性写进去，反序列化时撞上
 * 「未知属性」直接抛异常，指标被静默清零。{@link JsonIgnoreProperties} 则是留一道保险：
 * 以后有人再加派生 getter，也不会把旧的 checkpoint 读坏。
 *
 * @param compiled        编译是否通过
 * @param exitCode        沙箱进程退出码
 * @param testsTotal      执行的单测数
 * @param testsPassed     通过数
 * @param testsFailed     失败数
 * @param testsSkipped    跳过数
 * @param coverage        行覆盖率（JaCoCo），采集失败时为 -1
 * @param failureExcerpt  失败摘要，成功时为空字符串
 * @param durationMs      沙箱执行耗时
 * @param timedOut        是否超时被杀
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerifyResult(
        boolean compiled,
        int exitCode,
        int testsTotal,
        int testsPassed,
        int testsFailed,
        int testsSkipped,
        double coverage,
        String failureExcerpt,
        long durationMs,
        boolean timedOut
) {

    /** 是否通过验证：编译过了、没超时、且没有失败的单测。 */
    @JsonIgnore
    public boolean isGreen() {
        return compiled && !timedOut && testsFailed == 0;
    }

    /** 该工程是否真的有单测 —— 没有单测时「通过率」是没有意义的，要在指标里体现出来。 */
    @JsonIgnore
    public boolean hasTests() {
        return testsTotal > 0;
    }

    /** 供 REWRITE 节点组装重试 prompt 用的简短原因。 */
    @JsonIgnore
    public String failureReason() {
        if (timedOut) {
            return "沙箱执行超时被杀（" + durationMs + " ms）";
        }
        if (!compiled) {
            return "编译失败";
        }
        if (testsFailed > 0) {
            return testsFailed + " 个单测失败（共 " + testsTotal + " 个）";
        }
        return "";
    }
}
