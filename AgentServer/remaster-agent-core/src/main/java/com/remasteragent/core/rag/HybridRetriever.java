package com.remasteragent.core.rag;

import com.remasteragent.common.rag.CodeChunk;
import com.remasteragent.common.rag.RetrievedChunk;
import com.remasteragent.llm.embedding.EmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 混合检索 —— 代码 RAG 的查询入口。
 *
 * <h2>三路召回 + RRF 融合</h2>
 * <ul>
 *   <li><b>向量路</b>：语义相近。查「处理日期的代码」能召回到没出现「日期」二字的实现。</li>
 *   <li><b>全文路</b>：tsvector 关键词。查具体标识符时最直接。</li>
 *   <li><b>符号路</b>：类名/方法名的模糊匹配。<b>代码检索里最「准」的一路</b> ——
 *       用户要找 {@code OrderService}，符号匹配的确定性远高于向量。</li>
 * </ul>
 * <p>三路各有盲区，用 <b>RRF（Reciprocal Rank Fusion）</b>融合：{@code score = Σ 1/(k + rank)}。
 * 它<b>只看排名、不看原始分数</b> —— 因为三路的分数根本不可比（余弦相似度、ts_rank、
 * trgm similarity 是三个量纲），强行归一化只会引入主观权重。RRF 用排名这个「天然可比的量」
 * 绕开了这个坑，且不需要任何调参（k 取经典值 60）。
 *
 * <h2>邻居扩展</h2>
 * <p>检索命中的「一个方法」往往看不出它为什么这么写。这里把命中块的<b>所属类型骨架</b>
 * 一并带进来（方法 → 它所在的类），让模型能看到字段与其它方法的关系。
 * 这是代码检索区别于普通文本 RAG 的关键一环，也是「理解整库」而非「看懂一个函数」的落点。
 */
@Component
public class HybridRetriever implements ContextRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final CodeIndexStore store;
    private final EmbeddingProvider embeddingProvider;
    private final RagProperties properties;

    public HybridRetriever(CodeIndexStore store,
                           EmbeddingProvider embeddingProvider,
                           RagProperties properties) {
        this.store = store;
        this.embeddingProvider = embeddingProvider;
        this.properties = properties;
    }

    /**
     * 检索与 {@code query} 相关的代码块。
     *
     * <p>任何一路失败都不会让整体失败：某一路报错就打 WARN 少一路召回，
     * 其余路照常 —— 检索是「增强」，不该成为任务失败的单点。
     *
     * @param projectRoot 工程根目录（与索引时同一个工程）
     * @param query       检索意图，通常是「文件路径 + 类名 + 方法名」的拼接
     * @return 融合并按排名排序的命中；未启用、未索引或工程不存在时返回空列表
     */
    @Override
    public List<RetrievedChunk> retrieve(String projectRoot, String query) {
        if (!properties.enabled()) {
            return List.of();
        }
        String root = Paths.get(projectRoot).toAbsolutePath().normalize().toString();
        Optional<Long> repoId = store.findRepoId(root);
        if (repoId.isEmpty()) {
            log.debug("工程尚未建立索引，跳过检索: {}", root);
            return List.of();
        }
        long id = repoId.get();

        Map<String, List<CodeChunk>> hits = new LinkedHashMap<>();
        hits.put(RetrievedChunk.SOURCE_VECTOR, vectorHits(id, query));
        hits.put(RetrievedChunk.SOURCE_KEYWORD, keywordHits(id, query));
        hits.put(RetrievedChunk.SOURCE_SYMBOL, symbolHits(id, query));

        // key → 块，以及 每路 → 有序的 key 列表
        Map<String, CodeChunk> byKey = new LinkedHashMap<>();
        Map<String, List<String>> rankedBySource = new LinkedHashMap<>();
        for (Map.Entry<String, List<CodeChunk>> entry : hits.entrySet()) {
            List<String> keys = new ArrayList<>();
            for (CodeChunk chunk : entry.getValue()) {
                String key = keyOf(chunk);
                byKey.putIfAbsent(key, chunk);
                keys.add(key);
            }
            if (!keys.isEmpty()) {
                rankedBySource.put(entry.getKey(), keys);
            }
        }
        if (byKey.isEmpty()) {
            // 也走 INFO 而不是 DEBUG：索引已存在却三路全空，多半是「查询串没抽到可用词」
            // 或「trgm 索引没建」，是需要被看见的信号，不该只留在 debug 里。
            log.info("混合检索无命中: 工程={} 查询={}", root, QueryTerms.symbolFragment(query));
            return List.of();
        }

        List<FusedItem> fused = fuse(properties.rrfK(), rankedBySource);
        List<RetrievedChunk> result = assemble(id, fused, byKey);

        // 把「检索到底贡献了什么」显式打出来：只剩一行 INFO，却能回答
        // 「这次改写有没有拿到跨文件上下文」「哪一路在起作用」。
        // 没有这行，向量路悄悄禁用 / 检索全空都会表现成「任务正常但上下文变少」，无从察觉。
        log.info("混合检索命中 {} 块｜候选 向量={} 全文={} 符号={}｜查询={}",
                result.size(),
                hits.get(RetrievedChunk.SOURCE_VECTOR).size(),
                hits.get(RetrievedChunk.SOURCE_KEYWORD).size(),
                hits.get(RetrievedChunk.SOURCE_SYMBOL).size(),
                QueryTerms.symbolFragment(query));
        return result;
    }

    // ------------------------------------------------------------------
    // 每一路
    // ------------------------------------------------------------------

    private List<CodeChunk> vectorHits(long repoId, String query) {
        if (!embeddingProvider.available()) {
            return List.of();
        }
        try {
            float[] queryVector = embeddingProvider.embed(query);
            return store.searchByVector(repoId, queryVector, properties.candidateK());
        } catch (Exception e) {
            log.warn("向量检索路失败，本次跳过（其余路照常）: {}", e.getMessage());
            return List.of();
        }
    }

    private List<CodeChunk> keywordHits(long repoId, String query) {
        String tsQuery = QueryTerms.toTsQuery(query);
        if (tsQuery == null) {
            return List.of();
        }
        try {
            return store.searchByKeyword(repoId, tsQuery, properties.candidateK());
        } catch (Exception e) {
            log.warn("全文检索路失败，本次跳过: {}", e.getMessage());
            return List.of();
        }
    }

    private List<CodeChunk> symbolHits(long repoId, String query) {
        String fragment = QueryTerms.symbolFragment(query);
        if (fragment == null) {
            return List.of();
        }
        try {
            return store.searchBySymbol(repoId, fragment, properties.candidateK());
        } catch (Exception e) {
            log.warn("符号检索路失败，本次跳过: {}", e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // RRF 融合（纯函数，可单测）
    // ------------------------------------------------------------------

    /**
     * RRF 融合：{@code score(key) = Σ_source 1 / (k + rank_source(key))}。
     *
     * <p>只依赖各路的「排名」：某块在向量路排第 1、在符号路排第 5，就各贡献一份分数。
     * 这样「多路都认可」的块会自然浮到前面，比任何手工权重都稳。
     *
     * @param rrfK          平滑常数（经典 60）
     * @param rankedBySource 每路的有序 key 列表（排名即下标 + 1）
     * @return 按融合分降序排列；分数相同则保持首次出现的顺序（稳定排序）
     */
    static List<FusedItem> fuse(int rrfK, Map<String, List<String>> rankedBySource) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Set<String>> sources = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : rankedBySource.entrySet()) {
            String source = entry.getKey();
            List<String> keys = entry.getValue();
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                double contribution = 1.0 / (rrfK + i + 1);
                scores.merge(key, contribution, Double::sum);
                sources.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(source);
            }
        }

        return scores.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, Double> e) -> e.getValue()).reversed())
                .map(e -> new FusedItem(e.getKey(), e.getValue(), List.copyOf(sources.get(e.getKey()))))
                .toList();
    }

    // ------------------------------------------------------------------
    // 组装 + 邻居扩展
    // ------------------------------------------------------------------

    private List<RetrievedChunk> assemble(long repoId, List<FusedItem> fused, Map<String, CodeChunk> byKey) {
        Map<String, List<String>> selected = new LinkedHashMap<>();
        for (FusedItem item : fused) {
            if (selected.size() >= properties.topK()) {
                break;
            }
            selected.put(item.key(), item.sources());
        }

        if (properties.neighborDepth() > 0) {
            addStructuralNeighbors(repoId, selected, byKey);
        }
        if (properties.neighborDepth() > 1) {
            addDependencyNeighbors(repoId, selected, byKey);
        }

        List<RetrievedChunk> result = new ArrayList<>(selected.size());
        int rank = 1;
        for (Map.Entry<String, List<String>> entry : selected.entrySet()) {
            CodeChunk chunk = byKey.get(entry.getKey());
            if (chunk != null) {
                result.add(new RetrievedChunk(chunk, rank++, entry.getValue()));
            }
        }
        return result;
    }

    /**
     * 结构邻居扩展：把命中块的「所属类型骨架」补进来。
     *
     * <p>做法：对每个命中块求它所属的类型符号（方法 {@code Type#m} → {@code Type}，
     * 类型块取自己），再一次性把这些类型的 CLASS 块取回来。一次查询搞定，不产生 N+1。
     *
     * <p><b>为什么是「结构邻居」而不是完整的跨文件依赖图</b>：精确的跨文件依赖需要
     * symbol-solver 做全量符号解析，成本高、且对「看懂一段代码」的边际收益递减；
     * 而「方法 → 它的类」覆盖了最常见的理解断点（只看到方法看不到字段与同类其它方法）。
     * 跨文件依赖图属于后续增强，接口（{@code neighbor-depth}）已经留好。
     */
    private void addStructuralNeighbors(long repoId, Map<String, List<String>> selected,
                                        Map<String, CodeChunk> byKey) {
        Set<String> ownerTypes = new LinkedHashSet<>();
        for (String key : selected.keySet()) {
            CodeChunk chunk = byKey.get(key);
            if (chunk == null) {
                continue;
            }
            String ownerType = ownerTypeOf(chunk);
            if (ownerType != null) {
                ownerTypes.add(ownerType);
            }
        }
        if (ownerTypes.isEmpty()) {
            return;
        }

        List<CodeChunk> classChunks;
        try {
            classChunks = store.findChunksBySymbols(repoId, new ArrayList<>(ownerTypes));
        } catch (Exception e) {
            log.warn("邻居扩展查询失败，跳过: {}", e.getMessage());
            return;
        }
        for (CodeChunk chunk : classChunks) {
            if (!CodeChunk.KIND_CLASS.equals(chunk.kind())) {
                continue;
            }
            String key = keyOf(chunk);
            if (selected.containsKey(key)) {
                continue;
            }
            byKey.putIfAbsent(key, chunk);
            selected.put(key, List.of(RetrievedChunk.SOURCE_NEIGHBOR));
        }
    }

    /**
     * 跨文件依赖图多跳扩展：在「方法 → 所属类」（depth=1）之上，再按调用边跨文件扩展。
     *
     * <p>种子是已选中集合里的所有<b>方法块</b>符号；用 {@link DependencyExpander} 做 BFS，
     * 每跳通过存储层把名字级候选（如 {@code OrderService}）后缀匹配到全限定符号
     * （{@code com.foo.OrderService}），把「被调用目标的跨文件实现」带进上下文。</p>
     */
    private void addDependencyNeighbors(long repoId, Map<String, List<String>> selected,
                                         Map<String, CodeChunk> byKey) {
        Set<String> methodSeeds = selected.keySet().stream()
                .map(byKey::get)
                .filter(chunk -> chunk != null && CodeChunk.KIND_METHOD.equals(chunk.kind()))
                .map(HybridRetriever::keyOf)
                .collect(Collectors.toSet());
        if (methodSeeds.isEmpty()) {
            return;
        }
        int extraDepth = properties.neighborDepth() - 1;
        Set<String> expanded = DependencyExpander.expand(methodSeeds, extraDepth,
                front -> lookupCallees(repoId, front));
        if (expanded.isEmpty()) {
            return;
        }
        // 控制邻居总量，避免上下文被无关同名类型稀释：额外邻居不超过 topK
        List<String> toFetch = expanded.stream()
                .filter(key -> !selected.containsKey(key))
                .limit(properties.topK())
                .toList();
        if (toFetch.isEmpty()) {
            return;
        }
        try {
            List<CodeChunk> chunks = store.findChunksBySymbols(repoId, toFetch);
            for (CodeChunk chunk : chunks) {
                String key = keyOf(chunk);
                if (selected.containsKey(key)) {
                    continue;
                }
                byKey.putIfAbsent(key, chunk);
                selected.put(key, List.of(RetrievedChunk.SOURCE_NEIGHBOR));
            }
        } catch (Exception e) {
            log.warn("依赖图邻居扩展查询失败，跳过: {}", e.getMessage());
        }
    }

    /** 给定一组全限定符号，返回它们的被调用目标（全限定）：先取名字级候选，再后缀匹配落点。 */
    private Set<String> lookupCallees(long repoId, Set<String> front) {
        List<String> candidates = store.findCallTargets(repoId, new ArrayList<>(front));
        if (candidates.isEmpty()) {
            return Set.of();
        }
        List<CodeChunk> chunks = store.findChunksBySymbolSuffixes(repoId, candidates);
        return chunks.stream().map(HybridRetriever::keyOf).collect(Collectors.toSet());
    }

    /** 求一个块所属的类型符号：方法块取 {@code #} 之前，类型块取自己，其余返回 null。 */
    private static String ownerTypeOf(CodeChunk chunk) {
        String symbol = chunk.symbol();
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        int hash = symbol.indexOf('#');
        return hash > 0 ? symbol.substring(0, hash) : symbol;
    }

    /** 块的唯一键。同一文件同一符号即同一块（start_line 可能因重切而变化，不纳入）。 */
    static String keyOf(CodeChunk chunk) {
        return chunk.filePath() + "::" + (chunk.symbol() == null ? "" : chunk.symbol());
    }

    /** 融合后的中间项：key + 分数 + 命中来源。 */
    record FusedItem(String key, double score, List<String> sources) {
    }
}
