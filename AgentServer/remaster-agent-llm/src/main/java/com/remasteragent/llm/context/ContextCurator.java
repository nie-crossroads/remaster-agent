package com.remasteragent.llm.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文治理的执行器 —— 纯函数、无 LLM / DB / Maven 依赖，可确定性单测。
 *
 * <p>职责：
 * <ol>
 *   <li><b>去重</b>：按正文去重，同一段代码只保留优先级最高（数字最小）的那一份；</li>
 *   <li><b>重排</b>：按优先级升序（RRF 排名越靠前越先保住）；</li>
 *   <li><b>预算裁剪</b>：贪心填充，累计估算 token 不超过 {@link ContextBudget#maxTokens()}；</li>
 *   <li><b>保底</b>：若首个项就超过预算，仍保留它（宁可超一点也不让上下文为空）；</li>
 *   <li><b>统计</b>：返回丢弃数与溢出标记，供可观测日志使用。</li>
 * </ol>
 *
 * <p>注意：真正要改的源码（{@code sourceContent}）不在这里处理 —— 它在 prompt 里单独追加、
 * 永远在场，治理只管「相关代码」这一节。
 */
public final class ContextCurator {

    private ContextCurator() {
    }

    public static CuratedContext curate(ContextBudget budget, List<ContextItem> items) {
        if (items == null || items.isEmpty()) {
            return CuratedContext.empty();
        }
        if (!budget.enabled()) {
            int tokens = items.stream().mapToInt(i -> i.estimatedTokens(budget.estimator())).sum();
            return new CuratedContext(items, tokens, 0, false);
        }

        // 1) 去重：同一正文只留优先级最高（priority 最小）的那份
        Map<String, ContextItem> byContent = new LinkedHashMap<>();
        for (ContextItem item : items) {
            ContextItem prev = byContent.get(item.content());
            if (prev == null || item.priority() < prev.priority()) {
                byContent.put(item.content(), item);
            }
        }

        // 2) 重排：按优先级升序（稳定排序）
        List<ContextItem> sorted = new ArrayList<>(byContent.values());
        sorted.sort(Comparator.comparingInt(ContextItem::priority));

        // 3) 贪心填充 + 4) 保底
        List<ContextItem> selected = new ArrayList<>();
        int used = 0;
        for (ContextItem item : sorted) {
            int t = item.estimatedTokens(budget.estimator());
            if (!selected.isEmpty() && used + t > budget.maxTokens()) {
                continue; // 放不下且已有保底项，丢弃
            }
            selected.add(item);
            used += t;
        }

        int dropped = sorted.size() - selected.size();
        return new CuratedContext(selected, used, dropped, dropped > 0);
    }
}
