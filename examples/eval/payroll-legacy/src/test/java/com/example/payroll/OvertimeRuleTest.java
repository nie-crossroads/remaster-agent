package com.example.payroll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link OvertimeRule} 的行为契约 —— 迁移前后必须逐条为真。
 *
 * <p>只断言排序结果（工时降序、同工时按员工号升序）与「不改原列表」的副作用，
 * 不关心内部是匿名 Comparator 还是 {@code Comparator.comparing}。
 */
class OvertimeRuleTest {

    private static OvertimeRule.WorkRecord rec(int empId, int hours) {
        return new OvertimeRule.WorkRecord(empId, hours);
    }

    /** 把排序结果投影成「员工号:工时」序列，避免依赖 WorkRecord 的 equals。 */
    private static List<String> asTuples(List<OvertimeRule.WorkRecord> ranked) {
        return ranked.stream()
                .map(r -> r.empId() + ":" + r.hours())
                .toList();
    }

    @Test
    @DisplayName("按工时降序、同工时按员工号升序")
    void ranksByHoursThenEmpId() {
        List<OvertimeRule.WorkRecord> ranked = new OvertimeRule().rank(
                List.of(rec(1, 8), rec(2, 8), rec(3, 5)));

        assertEquals(List.of("1:8", "2:8", "3:5"), asTuples(ranked));
    }

    @Test
    @DisplayName("不改原列表（返回的是副本）")
    void returnsCopyNotMutation() {
        List<OvertimeRule.WorkRecord> input = List.of(rec(1, 8), rec(2, 8), rec(3, 5));
        List<OvertimeRule.WorkRecord> ranked = new OvertimeRule().rank(input);

        assertEquals(List.of("1:8", "2:8", "3:5"), asTuples(input),
                "原列表顺序不应被改变");
        assertEquals(3, ranked.size());
    }

    @Test
    @DisplayName("空列表与单元素")
    void emptyAndSingle() {
        assertEquals(List.of(), asTuples(new OvertimeRule().rank(List.of())));
        assertEquals(List.of("7:3"), asTuples(new OvertimeRule().rank(List.of(rec(7, 3)))));
    }
}
