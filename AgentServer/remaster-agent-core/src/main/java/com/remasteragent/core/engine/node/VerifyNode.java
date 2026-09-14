package com.remasteragent.core.engine.node;

import com.remasteragent.common.agent.VerifyResult;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeExecutor;
import com.remasteragent.core.engine.NodeOutcome;
import com.remasteragent.tools.maven.MavenResultParser;
import com.remasteragent.tools.sandbox.SandboxExecutor;
import com.remasteragent.tools.sandbox.SandboxRequest;
import com.remasteragent.tools.sandbox.SandboxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * VERIFY 节点：在沙箱里真实构建并跑测试，这是全链路唯一的「真相来源」。
 *
 * <p>整个项目的立论就压在这个节点上：模型说它改对了没有意义，编译通过率、单测通过率、
 * 覆盖率这些数字只能由真实的 Maven 执行给出。这也是它区别于「AI 改代码 demo」的地方 ——
 * demo 到「模型输出了新代码」就结束了，而真正的工程从这里才开始。
 *
 * <p>失败时返回的 {@link NodeOutcome} 同时携带结果对象与错误文本：结果对象会落进
 * checkpoint（保留「当时离通过还差多少」的证据），错误文本会喂回下一次重写
 * （告诉模型具体错在哪，而不是让它盲目再猜一遍）。
 */
@Component
public class VerifyNode implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(VerifyNode.class);

    public static final String NODE_KEY = "verify";

    /**
     * 某个文件的验证节点键：{@code verify:<相对路径>}，与 {@link RewriteNode#nodeKey(String)} 对称。
     *
     * <p>验证本身跑的是整个工程（{@code mvn test}），并不针对单个文件；键里带文件是为了让
     * 调度器能定位「这个 VERIFY 属于哪个文件的第几轮」，从而在回退时精确匹配 ——
     * 多文件场景下这是区分并存的多条改验证链的唯一依据。
     */
    public static String nodeKey(String filePath) {
        return NODE_KEY + ":" + filePath;
    }

    /** 单次沙箱执行的内存上限，从沙箱配置透传。 */
    private static final int MEMORY_LIMIT_MB = 1024;

    private final SandboxExecutor sandboxExecutor;
    private final CoreProperties coreProperties;

    public VerifyNode(SandboxExecutor sandboxExecutor, CoreProperties coreProperties) {
        this.sandboxExecutor = sandboxExecutor;
        this.coreProperties = coreProperties;
    }

    @Override
    public NodeType type() {
        return NodeType.VERIFY;
    }

    @Override
    public NodeOutcome execute(NodeContext context) {
        List<String> command = buildCommand();

        SandboxResult sandboxResult = sandboxExecutor.execute(SandboxRequest
                .builder(context.workspace(), command)
                .timeout(Duration.ofMinutes(10))
                .memoryLimitMb(MEMORY_LIMIT_MB)
                .build());

        VerifyResult verify = MavenResultParser.toVerifyResult(sandboxResult, context.workspace());

        log.info("沙箱验证结果: 编译={} 单测 {}/{} 覆盖率={} 耗时={}ms",
                verify.compiled(), verify.testsPassed(), verify.testsTotal(),
                verify.coverage() < 0 ? "未采集" : String.format("%.1f%%", verify.coverage() * 100),
                verify.durationMs());

        if (verify.isGreen()) {
            return NodeOutcome.ok(verify);
        }

        String feedback = buildFailureFeedback(verify);
        warnIfFeedbackIsBlind(verify);
        return new NodeOutcome(false, verify, feedback);
    }

    /**
     * 失败却拿不到任何定位信息时告警 —— 这会让回退重写变成「盲改」。
     *
     * <p>这是端到端验收暴露出的真实问题：沙箱日志因编码问题读取失败时，控制台文本变成空串，
     * {@code failureExcerpt} 随之落空，模型只拿到一句「验证未通过：编译失败」就去猜着改下一版，
     * 白白烧掉重试配额与 token。日志里加一条 WARN，是为了让这种<b>静默降级</b>在监控上可见，
     * 而不是表现成「任务失败了但看不出为什么」。
     */
    private void warnIfFeedbackIsBlind(VerifyResult verify) {
        if (verify.failureExcerpt() == null || verify.failureExcerpt().isBlank()) {
            log.warn("验证未通过（{}）但没有提取到可定位的错误详情，下一轮重写将缺少目标信息；"
                            + "请检查沙箱日志采集（stdout/stderr）与 surefire/jacoco 报告是否正常",
                    verify.failureReason());
        }
    }

    /**
     * 组装失败反馈。
     *
     * <p>只保留三类信息：失败原因一句话、失败的用例名、编译错误的定位行。
     * 把整段 Maven 日志喂回去是常见错误 —— prompt 里塞满 INFO 级噪声之后，
     * 模型反而更抓不住真正的错误，还会显著推高输入 token 成本。
     */
    private String buildFailureFeedback(VerifyResult verify) {
        StringBuilder sb = new StringBuilder();
        sb.append("验证未通过：").append(verify.failureReason()).append('\n');
        if (verify.failureExcerpt() != null && !verify.failureExcerpt().isBlank()) {
            sb.append(verify.failureExcerpt());
        }
        return sb.toString();
    }

    /** 逻辑命令：第一个元素 {@code mvn} 由沙箱实现替换成真实可执行文件并补齐 settings / 本地仓库。 */
    private List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        command.add("mvn");
        command.addAll(coreProperties.verifyMavenGoals());
        return command;
    }
}
