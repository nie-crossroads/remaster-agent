package com.example.payroll;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 加班排序 —— 迁移目标文件之一（匿名内部类 Comparator + 显式装箱 → lambda / 方法引用 / 自动装箱）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>匿名内部类实现 {@code Comparator}</b>：{@code new Comparator<WorkRecord>() { ... }}。</li>
 *   <li><b>显式装箱</b> {@code Integer.valueOf(a).compareTo(Integer.valueOf(b))}。</li>
 *   <li><b>复制后再排序</b> 用 {@code new ArrayList<WorkRecord>(records)}（菱形前的老写法）。</li>
 * </ul>
 *
 * <p>排序的语义（工时降序、同工时按员工号升序）是<b>可观测契约</b>，迁移后必须一致。
 */
public class OvertimeRule {

    /** 按工时降序排，工时相同按员工号升序；返回新列表，不改原列表。 */
    public List<WorkRecord> rank(List<WorkRecord> records) {
        List<WorkRecord> copy = new ArrayList<WorkRecord>(records);
        Collections.sort(copy, new Comparator<WorkRecord>() {
            @Override
            public int compare(WorkRecord a, WorkRecord b) {
                int byHours = Integer.valueOf(b.hours()).compareTo(Integer.valueOf(a.hours()));
                if (byHours != 0) {
                    return byHours;
                }
                return Integer.valueOf(a.empId()).compareTo(Integer.valueOf(b.empId()));
            }
        });
        return copy;
    }

    /** 一条加班记录。 */
    public static class WorkRecord {

        private final int empId;
        private final int hours;

        public WorkRecord(int empId, int hours) {
            this.empId = empId;
            this.hours = hours;
        }

        public int empId() {
            return empId;
        }

        public int hours() {
            return hours;
        }
    }
}
