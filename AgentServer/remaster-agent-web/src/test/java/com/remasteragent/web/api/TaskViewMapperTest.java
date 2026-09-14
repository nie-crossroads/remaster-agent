package com.remasteragent.web.api;

import com.remasteragent.common.agent.PlanResult;
import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.PatchRecord;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.web.api.dto.TaskDetailView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TaskViewMapper} 把 PLAN 节点摊成评审视图的确定性单测。
 *
 * <p>为什么专门测这一段：前端评审界面完全依赖这个结构，而它是<b>跨进程读一份 JSON</b>
 * —— 写的人（Worker 里的 PlanNode）和读的人（API 里的本类）隔着进程和版本。
 * 这种「写读两端各改各的」最容易出的问题是形状悄悄漂移，而现象只是界面上计划区域空白，
 * 既不报错也没有日志。把这个契约钉在单测里，比在界面上用眼睛确认可靠得多。
 */
class TaskViewMapperTest {

    private final JsonCodec json = new JsonCodec();
    private final TaskViewMapper mapper = new TaskViewMapper(json);

    @Test
    void 计划被摊平成评审视图且顺序保持() {
        PlanResult plan = new PlanResult("把遗留的订单统计模块迁到 java.time", List.of(
                new PlanResult.PlanStep("src/main/java/com/example/legacy/OrderService.java", "用了 java.util.Date"),
                new PlanResult.PlanStep("src/main/java/com/example/legacy/ReportService.java", "用了 SimpleDateFormat")));

        TaskDetailView detail = mapper.toDetail(
                task(), List.of(planNode(1L, NodeStatus.SUCCEEDED, plan)), List.of(),
                TaskStore.CostSummary.empty(), false);

        assertTrue(detail.plan() != null);
        assertEquals("把遗留的订单统计模块迁到 java.time", detail.plan().summary());
        assertEquals(2, detail.plan().steps().size());
        // 顺序即建议执行顺序，被摊平后不能乱 —— 评审界面按这个顺序展示
        assertEquals("src/main/java/com/example/legacy/OrderService.java",
                detail.plan().steps().get(0).filePath());
        assertEquals("用了 java.util.Date", detail.plan().steps().get(0).rationale());
        assertEquals("src/main/java/com/example/legacy/ReportService.java",
                detail.plan().steps().get(1).filePath());
        assertTrue(!detail.plan().approved(), "未批准时应为 false，前端据此显示批准按钮");
    }

    @Test
    void 批准标记透传到视图() {
        PlanResult plan = new PlanResult("概述", List.of(
                new PlanResult.PlanStep("A.java", "理由")));

        TaskDetailView detail = mapper.toDetail(
                task(), List.of(planNode(1L, NodeStatus.SUCCEEDED, plan)), List.of(),
                TaskStore.CostSummary.empty(), true);

        assertTrue(detail.plan() != null);
        assertTrue(detail.plan().approved());
    }

    @Test
    void 取最新的那个成功的PLAN节点() {
        PlanResult oldPlan = new PlanResult("旧计划", List.of(
                new PlanResult.PlanStep("Old.java", "")));
        PlanResult newPlan = new PlanResult("新计划", List.of(
                new PlanResult.PlanStep("New.java", "")));

        // 旧的成功节点 id 较小，新的成功节点 id 较大；中间还夹一个失败的 PLAN
        List<DagNode> nodes = List.of(
                planNode(1L, NodeStatus.SUCCEEDED, oldPlan),
                planNode(2L, NodeStatus.FAILED, newPlan),
                planNode(3L, NodeStatus.SUCCEEDED, newPlan));

        TaskDetailView detail = mapper.toDetail(task(), nodes, List.of(),
                TaskStore.CostSummary.empty(), false);

        assertTrue(detail.plan() != null);
        assertEquals("新计划", detail.plan().summary(),
                "Worker 重启后 PLAN 可能重跑，生效的必须是最新那份成功产出");
    }

    @Test
    void 没有PLAN节点时计划为空() {
        // 阶段 1 拓扑（ANALYZE → REWRITE → VERIFY）本就没有计划，
        // 前端必须能拿到 null 而不是空壳对象
        List<DagNode> nodes = List.of(
                new DagNode(1L, 7L, "analyze", NodeType.ANALYZE, List.of(),
                        NodeStatus.SUCCEEDED, 0, null, null, Instant.now(), Instant.now()));

        TaskDetailView detail = mapper.toDetail(task(), nodes, List.of(),
                TaskStore.CostSummary.empty(), false);

        assertNull(detail.plan());
    }

    @Test
    void 计划JSON坏掉时降级为空而不让详情接口失败() {
        DagNode broken = new DagNode(9L, 7L, "plan", NodeType.PLAN, List.of(1L),
                NodeStatus.SUCCEEDED, 0, "{ 这不是 JSON", null, Instant.now(), Instant.now());

        TaskDetailView detail = mapper.toDetail(task(), List.of(broken), List.of(),
                TaskStore.CostSummary.empty(), false);

        // 详情接口仍应正常返回，只是计划区域没东西 —— 历史任务的旧格式不该让整页打不开
        assertNull(detail.plan());
        assertEquals(TaskStatus.SUCCEEDED.name(), detail.task().status());
    }

    // ------------------------------------------------------------------

    @Test
    void 补丁带上产出它的节点轮次() {
        // 回退重写会给同一个文件产生多份补丁，光看文件名分不清哪份是哪轮 ——
        // 而轮次只存在节点上（patch 表只有 node_id），必须在映射时 join 出来。
        List<DagNode> nodes = List.of(
                rewriteNode(11L, 0),
                rewriteNode(12L, 1));

        TaskDetailView detail = mapper.toDetail(task(), nodes,
                List.of(patch(11L), patch(12L)), TaskStore.CostSummary.empty(), false);

        assertEquals(2, detail.patches().size());
        assertEquals(0, detail.patches().get(0).attempt());
        assertEquals(1, detail.patches().get(1).attempt(),
                "第二轮补丁必须带出 attempt=1，否则界面上两个同名标签无法区分");
        assertEquals("src/main/java/Demo.java", detail.patches().get(1).filePath());
    }

    @Test
    void 补丁找不到对应节点时轮次降级为0() {
        // 补丁与节点是同一事务写进去的，正常不会缺；但历史数据可能缺。
        // 降级为 0 而不是抛异常：为一个标签显示「第 1 轮」而让整页打不开，代价不对等。
        TaskDetailView detail = mapper.toDetail(task(), List.of(), List.of(patch(99L)),
                TaskStore.CostSummary.empty(), false);

        assertEquals(1, detail.patches().size());
        assertEquals(0, detail.patches().get(0).attempt());
    }

    // ------------------------------------------------------------------

    private static DagNode rewriteNode(Long id, int attempt) {
        return new DagNode(id, 7L, "rewrite:src/main/java/Demo.java", NodeType.REWRITE, List.of(1L),
                NodeStatus.SUCCEEDED, attempt, null, null, Instant.now(), Instant.now());
    }

    private static PatchRecord patch(long nodeId) {
        return new PatchRecord(nodeId, nodeId, "src/main/java/Demo.java",
                "@@ -1 +1 @@\n-old\n+new", "hash", true, Instant.now());
    }

    private static DagNode planNode(Long id, NodeStatus status, PlanResult plan) {
        return new DagNode(id, 7L, "plan", NodeType.PLAN, List.of(1L),
                status, 0, new JsonCodec().write(plan), null, Instant.now(), Instant.now());
    }

    private static MigrationTask task() {
        Instant now = Instant.now();
        return new MigrationTask(7L, "E:/demo", "src/main/java/Demo.java", 21,
                TaskStatus.SUCCEEDED, null, null, now, now);
    }
}
