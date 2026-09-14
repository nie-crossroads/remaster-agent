package com.remasteragent.core.rag;

import com.remasteragent.common.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合的确定性单测。
 *
 * <p>融合是整条检索链路里唯一「算错也不会报错、只会让结果悄悄变差」的环节 ——
 * 正因为如此，它必须被单测钉死：给定各路的排名，融合结果应该是唯一确定的。
 */
class HybridRetrieverFusionTest {

    @Test
    void 多路都命中的块排在最前() {
        Map<String, List<String>> bySource = new LinkedHashMap<>();
        bySource.put(RetrievedChunk.SOURCE_VECTOR, List.of("a", "b"));   // b 在向量路排第 2
        bySource.put(RetrievedChunk.SOURCE_SYMBOL, List.of("b", "c"));   // b 在符号路排第 1

        List<HybridRetriever.FusedItem> fused = HybridRetriever.fuse(60, bySource);

        // b 被两路同时命中（1/62 + 1/61），必然超过只被一路命中的 a（1/61）与 c（1/62）
        assertEquals("b", fused.get(0).key());
        assertEquals(List.of("a", "c"), List.of(fused.get(1).key(), fused.get(2).key()));
    }

    @Test
    void 记录每个块被哪几路命中() {
        Map<String, List<String>> bySource = new LinkedHashMap<>();
        bySource.put(RetrievedChunk.SOURCE_VECTOR, List.of("a", "b"));
        bySource.put(RetrievedChunk.SOURCE_SYMBOL, List.of("b", "c"));

        List<HybridRetriever.FusedItem> fused = HybridRetriever.fuse(60, bySource);
        HybridRetriever.FusedItem shared = fused.stream()
                .filter(item -> item.key().equals("b"))
                .findFirst().orElseThrow();

        assertEquals(2, shared.sources().size(), "b 应记录被两路命中");
        assertTrue(shared.sources().containsAll(List.of(
                RetrievedChunk.SOURCE_VECTOR, RetrievedChunk.SOURCE_SYMBOL)));
    }

    @Test
    void 空输入返回空结果() {
        assertTrue(HybridRetriever.fuse(60, Map.of()).isEmpty());
    }

    @Test
    void rrfK越小排名差异放得越大() {
        // 单路时，第 1 名与第 2 名分数之比 = (rrfK+2)/(rrfK+1)：k 越小，差距越显著
        double smallK = scoreOfSecondOverFirst(1);
        double largeK = scoreOfSecondOverFirst(100);
        assertTrue(smallK < largeK, "k 越小，排名靠前的块优势越明显（分数比更小）");
    }

    private static double scoreOfSecondOverFirst(int rrfK) {
        Map<String, List<String>> bySource = Map.of(RetrievedChunk.SOURCE_VECTOR, List.of("first", "second"));
        List<HybridRetriever.FusedItem> fused = HybridRetriever.fuse(rrfK, bySource);
        double first = fused.get(0).score();
        double second = fused.get(1).score();
        return second / first;
    }
}
