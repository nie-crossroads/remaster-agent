package com.remasteragent.core.engine.node;

import com.remasteragent.common.domain.GateStatus;
import com.remasteragent.common.domain.HumanGate;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeOutcome;
import com.remasteragent.core.store.InMemoryTaskStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GateNode} 的确定性单测 —— 不依赖数据库、不调模型。
 *
 * <p>验证三件在人在回路里最容易写错的事：挂起语义正确（既不是成功也不是失败）、
 * 落下的门禁行齐全（状态 PENDING、指向正确的节点）、重复执行幂等（不会落两张待办）。
 */
class GateNodeTest {

    private final InMemoryTaskStore store = new InMemoryTaskStore();
    private final GateNode gateNode = new GateNode(store);

    private NodeContext contextFor(long taskId, String filePath) {
        String key = GateNode.nodeKey(filePath);
        store.insertNode(taskId, key, NodeType.GATE, List.of(), 0);
        var node = store.findNode(taskId, key, 0).orElseThrow();
        return new NodeContext(store.findTask(taskId).orElseThrow(), node, Path.of("."), null);
    }

    @Test
    @DisplayName("首次执行：落一行 PENDING 门禁并返回挂起（既非成功也非失败）")
    void firstRunCreatesGateAndSuspends() {
        long taskId = store.createTask("/tmp/proj", "A.java", 21, null);
        NodeContext context = contextFor(taskId, "com/foo/A.java");

        NodeOutcome outcome = gateNode.execute(context);

        assertTrue(outcome.suspended(), "GATE 节点应返回挂起");
        assertFalse(outcome.success(), "挂起不是成功 —— 否则下游会立刻开跑，门禁形同虚设");
        assertNotNull(outcome.result());

        List<HumanGate> gates = store.findGates(taskId);
        assertEquals(1, gates.size(), "应恰好落一行门禁");
        HumanGate gate = gates.get(0);
        assertEquals(GateStatus.PENDING, gate.status());
        assertEquals(context.node().id(), gate.nodeId(), "门禁必须指向当前 GATE 节点");
        assertTrue(gate.comment().contains("com/foo/A.java"),
                "挂起说明应带上被拦下的文件，实际为: " + gate.comment());
        assertTrue(store.findOpenGate(taskId).isPresent(), "应能查到等待中的门禁");
    }

    @Test
    @DisplayName("重复执行同一节点：复用已有门禁，不落第二行（一次挂起不等于两张待办）")
    void rerunReusesExistingGate() {
        long taskId = store.createTask("/tmp/proj", "A.java", 21, null);
        NodeContext context = contextFor(taskId, "com/foo/A.java");

        gateNode.execute(context);
        NodeOutcome second = gateNode.execute(context);

        assertTrue(second.suspended());
        assertEquals(1, store.findGates(taskId).size(), "重复执行不得新增门禁行");
    }

    @Test
    @DisplayName("节点键：带文件用 gate:<path>，无文件退回裸键 gate")
    void nodeKeyEncoding() {
        assertEquals("gate", GateNode.NODE_KEY);
        assertEquals("gate:com/foo/A.java", GateNode.nodeKey("com/foo/A.java"));
        assertEquals("gate", GateNode.nodeKey(null));
        assertEquals("gate", GateNode.nodeKey("  "));
    }
}
