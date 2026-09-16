package com.remasteragent.core.store;

import com.remasteragent.common.domain.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TaskStore#countVerifyRounds(long)} 的口径守卫。
 *
 * <h2>为什么要为「一个统计」单独开一个测试类</h2>
 * <p>这个数会被 {@code TaskMetrics.verifyAttempts} 拿去当「回退轮次」，
 * 再被 {@code retried()} 读成「是否发生过回退重写」，最终显示在前端与评测报告里。
 * 早先它统计的是 VERIFY <b>节点总数</b>，于是一次规划了 4 个文件的干净迁移
 * （每个文件各跑一轮 VERIFY，attempt 都是 0）会被记成「4 轮」，
 * 前端显示「重试过」，评测报告的一次通过率直接归零 ——
 * 而实际上它一轮都没重试。
 *
 * <p>这类错误不会让任何测试变红，只会让数字悄悄撒谎，所以必须专门钉住。
 */
class CountVerifyRoundsTest {

    private static final String FILE_A = "verify:src/main/java/com/example/A.java";
    private static final String FILE_B = "verify:src/main/java/com/example/B.java";
    private static final String FILE_C = "verify:src/main/java/com/example/C.java";

    @Test
    @DisplayName("多文件各跑一轮 = 1 轮，不是 N 轮（这是本类存在的理由）")
    void multiFileSingleRoundCountsAsOne() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = store.createTask("E:/proj", "src/main/java/com/example/A.java", 21);

        for (String key : List.of(FILE_A, FILE_B, FILE_C)) {
            store.insertNode(taskId, key, NodeType.VERIFY, List.of(), 0);
        }

        assertEquals(3, store.findNodes(taskId).size(), "确实插了 3 个 VERIFY 节点");
        assertEquals(1, store.countVerifyRounds(taskId),
                "3 个文件各跑一轮是 1 轮，不是 3 轮 —— 否则会被误判成回退了 2 次");
    }

    @Test
    @DisplayName("回退时取最大 attempt + 1")
    void retriesRaiseTheRoundCount() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = store.createTask("E:/proj", "src/main/java/com/example/A.java", 21);

        store.insertNode(taskId, FILE_A, NodeType.VERIFY, List.of(), 0);
        store.insertNode(taskId, FILE_A, NodeType.VERIFY, List.of(), 1);
        store.insertNode(taskId, FILE_B, NodeType.VERIFY, List.of(), 0);

        assertEquals(2, store.countVerifyRounds(taskId), "A 回退过一次 → 2 轮；B 只有 1 轮，取最大");
    }

    @Test
    @DisplayName("没有 VERIFY 节点时是 0（没跑过），不能报 1")
    void noVerifyNodesMeansZeroRounds() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = store.createTask("E:/proj", "src/main/java/com/example/A.java", 21);

        assertEquals(0, store.countVerifyRounds(taskId));
    }

    @Test
    @DisplayName("REWRITE 节点不参与轮次统计")
    void rewriteNodesAreIgnored() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = store.createTask("E:/proj", "src/main/java/com/example/A.java", 21);

        store.insertNode(taskId, "rewrite:src/main/java/com/example/A.java", NodeType.REWRITE, List.of(), 0);
        store.insertNode(taskId, "rewrite:src/main/java/com/example/A.java", NodeType.REWRITE, List.of(), 1);
        store.insertNode(taskId, FILE_A, NodeType.VERIFY, List.of(), 0);

        assertEquals(1, store.countVerifyRounds(taskId));
    }
}
