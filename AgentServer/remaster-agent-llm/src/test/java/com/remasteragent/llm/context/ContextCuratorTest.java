package com.remasteragent.llm.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContextCurator} 的确定性单测 —— 纯函数，无 LLM / DB / Maven 依赖。
 *
 * <p>覆盖：空输入、去重保高优先级、预算不足裁剪低优先级、预算充足全入选、关闭治理全量透传、
 * 以及 token 估算精度。这些正是「上下文治理」相对旧硬编码字符上限新增的可断言行为。
 */
class ContextCuratorTest {

    private static ContextItem item(int priority, String content) {
        return new ContextItem(priority, "t" + priority, "F" + priority + ".java",
                List.of("symbol"), content, "t" + priority);
    }

    @Test
    void 空输入返回空结果() {
        CuratedContext c = ContextCurator.curate(ContextBudget.DEFAULT, List.of());
        assertTrue(c.selected().isEmpty());
        assertEquals(0, c.droppedCount());
        assertFalse(c.overflow());
    }

    @Test
    void 去重保留高优先级副本() {
        ContextItem high = item(1, "same");
        ContextItem low = item(5, "same");
        // 顺序故意打乱：low 在前，high 在后，验证去重不看顺序
        CuratedContext c = ContextCurator.curate(ContextBudget.DEFAULT, List.of(low, high));
        // 去重后只剩 1 条（否则会出现 2 条）；且保留的是 priority 最小（最高优先级）的那份
        assertEquals(1, c.selected().size());
        assertEquals(1, c.selected().get(0).priority());
    }

    @Test
    void 预算不足时丢弃低优先级并标记溢出() {
        // 极小预算：每项渲染后都超过，但首项保底入选，其余丢弃
        ContextBudget tiny = ContextBudget.of(2, true);
        ContextItem a = item(1, "alpha");
        ContextItem b = item(2, "beta");
        ContextItem c = item(3, "gamma");
        CuratedContext r = ContextCurator.curate(tiny, List.of(a, b, c));
        assertEquals(1, r.selected().size());
        assertEquals(1, r.selected().get(0).priority());
        assertEquals(2, r.droppedCount());
        assertTrue(r.overflow());
    }

    @Test
    void 预算充足时全部入选且按优先级升序() {
        ContextBudget big = ContextBudget.of(100000, true);
        ContextItem a = item(1, "alpha");
        ContextItem b = item(2, "beta");
        CuratedContext r = ContextCurator.curate(big, List.of(a, b));
        assertEquals(2, r.selected().size());
        assertEquals(0, r.droppedCount());
        assertFalse(r.overflow());
        assertEquals(1, r.selected().get(0).priority());
        assertEquals(2, r.selected().get(1).priority());
    }

    @Test
    void 关闭治理时超预算也全量透传() {
        ContextBudget off = ContextBudget.disabled();
        ContextItem a = item(1, "alpha");
        ContextItem b = item(2, "beta");
        CuratedContext r = ContextCurator.curate(off, List.of(a, b));
        assertEquals(2, r.selected().size());
        assertEquals(0, r.droppedCount());
        assertFalse(r.overflow());
    }

    @Test
    void 估算器按字符数每四向上取整() {
        TokenEstimator e = CharBasedTokenEstimator.codeDefault();
        assertEquals(0, e.estimate(""));
        assertEquals(0, e.estimate(null));
        assertEquals(2, e.estimate("12345678"));   // 8 / 4 = 2
        assertEquals(3, e.estimate("123456789"));   // 9 / 4 向上取整 = 3
    }
}
