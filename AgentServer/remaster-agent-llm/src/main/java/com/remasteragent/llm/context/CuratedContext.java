package com.remasteragent.llm.context;

import java.util.List;

/**
 * 治理后的上下文结果。
 *
 * @param selected        入选的上下文项（已按优先级升序）
 * @param estimatedTokens 入选项估算 token 总量
 * @param droppedCount    被丢弃的项数
 * @param overflow        是否发生了溢出（有项被丢弃）
 */
public record CuratedContext(
        List<ContextItem> selected,
        int estimatedTokens,
        int droppedCount,
        boolean overflow) {

    public static CuratedContext empty() {
        return new CuratedContext(List.of(), 0, 0, false);
    }
}
