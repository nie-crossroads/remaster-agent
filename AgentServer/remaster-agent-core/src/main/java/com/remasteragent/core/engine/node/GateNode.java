package com.remasteragent.core.engine.node;

import com.remasteragent.common.domain.HumanGate;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeExecutor;
import com.remasteragent.core.engine.NodeOutcome;
import com.remasteragent.core.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * GATE 节点：DAG 里的一个「挂起点」—— 到此停下，等人放行。
 *
 * <h2>它与其他节点的根本不同</h2>
 * <p>ANALYZE / REWRITE / VERIFY 都是「干活，然后返回成功或失败」。GATE 不干活：
 * 它的全部作用是把执行流停在这里，落一行 {@code human_gate} 记录，然后返回
 * {@link NodeOutcome#suspend}。调度器见到挂起，会把任务置为 {@code WAITING_HUMAN}
 * 并立刻返回 —— <b>不占 Worker</b>。人工批准后任务重新入队，从 checkpoint 续跑，
 * 这个 GATE 节点已被判定为 SUCCEEDED，不会再次挂起。
 *
 * <h2>为什么把挂起做成节点的返回值，而不是调度器的特殊分支</h2>
 * <p>「这是一道门」是节点的语义，不是调度器的语义。调度器只该认识三件事：
 * 成功、失败、挂起。把「什么情况下该停」的判断塞进调度器，等于让编排层去理解
 * GATE 的业务含义 —— 那正是本项目坚持「调度器不认识 JavaParser、不认识沙箱」
 * 这条边界要避免的。节点说自己要挂起，调度器照做，仅此而已。
 *
 * <h2>幂等</h2>
 * <p>重复投递、Worker 重启都可能让这个节点被再次调度。若已经有一道等待中的门，
 * 这里直接复用、不再落新行 —— 否则一次挂起会变成两张待办，人批了一张任务也不会动。
 * 真正把「重复执行」挡在门外的是调度器的门禁判据（{@code pauseForGateIfNeeded}），
 * 这里的幂等是第二道保险。
 */
@Component
public class GateNode implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(GateNode.class);

    /** 无文件上下文时的裸节点键。 */
    public static final String NODE_KEY = "gate";

    private final TaskStore taskStore;

    public GateNode(TaskStore taskStore) {
        this.taskStore = taskStore;
    }

    @Override
    public NodeType type() {
        return NodeType.GATE;
    }

    /** 带文件的门禁节点键，形如 {@code gate:com/foo/OrderService.java}。 */
    public static String nodeKey(String filePath) {
        return filePath == null || filePath.isBlank() ? NODE_KEY : NODE_KEY + ":" + filePath;
    }

    @Override
    public NodeOutcome execute(NodeContext context) {
        long taskId = context.task().id();
        long nodeId = context.node().id();

        // 幂等：已经有一道等待中的门就复用，不重复落行
        Optional<HumanGate> existing = taskStore.findOpenGate(taskId);
        if (existing.isPresent()) {
            log.info("门禁已存在（gate #{}），复用并继续挂起", existing.get().id());
            return NodeOutcome.suspend(describe(context, existing.get().id()));
        }

        String filePath = filePathOf(context.node().nodeKey());
        String comment = buildComment(context.task(), filePath);
        long gateId = taskStore.insertGate(nodeId, comment);
        log.info("▶ 已挂起等待人工门禁 gate #{}（节点 {}，文件 {}）", gateId, nodeId, filePath);
        return NodeOutcome.suspend(describe(context, gateId));
    }

    /**
     * 门禁的说明文案 —— 审批人看到它才知道「要确认什么」。
     *
     * <p>它落进 {@code human_gate.comment} 的初值（被人审批后会被覆盖成审批意见），
     * 所以措辞要能独立说明「这一步为什么停下」。
     *
     * <p>三种情形分开说，因为它们要人确认的东西不同：整仓升级看的是 pom 补丁、
     * 常规改写着的是某个文件的补丁。把前者说成「已改写 xxx 文件」会让人去翻一个
     * 这个任务根本没碰过的文件。
     */
    private static String buildComment(MigrationTask task, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return task != null && task.entryFile() == null
                    ? "编译级别升级已完成，请确认全仓 pom 补丁后再继续验证"
                    : "改写已完成，请确认后再继续验证";
        }
        return "已改写 " + filePath + "，请确认补丁后再继续验证";
    }

    /** 挂起现场描述：节点 + 文件 + 说明。调度器用它拼进度事件。 */
    private static GateSuspend describe(NodeContext context, long gateId) {
        String filePath = filePathOf(context.node().nodeKey());
        return new GateSuspend(gateId, context.node().id(), filePath,
                buildComment(context.task(), filePath));
    }

    /** 取节点键中 ':' 之后的部分（文件路径）；裸键返回 null。 */
    private static String filePathOf(String nodeKey) {
        if (nodeKey == null) {
            return null;
        }
        int colon = nodeKey.indexOf(':');
        return (colon < 0 || colon == nodeKey.length() - 1) ? null : nodeKey.substring(colon + 1);
    }

    /** 挂起现场 —— 作为 {@link NodeOutcome#suspend} 的载荷，供调度器拼事件文案。 */
    public record GateSuspend(long gateId, long nodeId, String filePath, String message) {
    }
}
