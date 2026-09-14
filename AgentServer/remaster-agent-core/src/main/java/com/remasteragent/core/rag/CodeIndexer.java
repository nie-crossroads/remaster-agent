package com.remasteragent.core.rag;

import com.remasteragent.common.rag.CodeChunk;
import com.remasteragent.llm.embedding.EmbeddingProvider;
import com.remasteragent.tools.ast.CodeChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码索引器 —— 把整个工程切成块、算好向量、写进 {@code repo}/{@code code_chunk}。
 *
 * <p>它是 ANALYZE 阶段的副产物：理解一个文件之前，先把整个工程建成可检索的索引。
 * 阶段 1 只看单文件，索引显得多余；但一旦改写要「参考工程里别处的相似写法」，
 * 索引就是检索的前提。
 *
 * <h2>两条刻意的「宽容」设计</h2>
 * <ul>
 *   <li><b>单个文件切块失败只跳过，不中断。</b>工程里常有 {@code target/} 下的半成品、
 *       平台相关的源码；为一个坏文件让整次索引失败，得不偿失。</li>
 *   <li><b>向量化失败降级为「无向量」，不抛异常。</b>向量路是最可能被外部条件卡住的一路；
 *       让它失败拖垮整个索引，会连带把全文/符号两路也一起废掉。降级后 {@link IndexStats#vectorized()}
 *       会如实报 false，问题依然可见。</li>
 * </ul>
 */
@Component
public class CodeIndexer {

    private static final Logger log = LoggerFactory.getLogger(CodeIndexer.class);

    /** 单次索引的文件数上限 —— 防止误把某个巨大仓库整个吞进来，把内存和 token 打爆。 */
    private static final int MAX_FILES = 20;

    /**
     * 单块送进 embedding 的字符数上限。
     *
     * <p>超大方法（几千行的生成代码）超出模型输入上限会直接报错，而截断后仍保留了它的
     * 「前半段语义」——足以让它被检索到，比整块丢掉强。真正需要完整内容时，写进 prompt 的是
     * 库里的原文，不受这里影响。
     */
    private static final int MAX_EMBEDDING_CHARS = 6000;

    private final CodeIndexStore store;
    private final EmbeddingProvider embeddingProvider;

    public CodeIndexer(CodeIndexStore store, EmbeddingProvider embeddingProvider) {
        this.store = store;
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * 索引一个工程。幂等：同一个工程重复索引会覆盖上一次的结果。
     *
     * @param projectRoot 工程根目录
     * @param name        展示用仓库名（一般取目录名）
     */
    public IndexStats index(String projectRoot, String name) {
        Path root = Paths.get(projectRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("工程目录不存在: " + root);
        }

        long repoId = store.upsertRepo(root.toString(), name);
        store.deleteChunks(repoId);

        Collected collected = collectChunks(root);
        List<float[]> vectors = embedAll(collected.chunks());
        store.insertChunks(repoId, collected.chunks(), vectors);

        boolean vectorized = vectors != null;
        log.info("代码索引完成: repo={} 名称={} 文件={} 块={} 向量={}",
                repoId, name, collected.files(), collected.chunks().size(), vectorized ? "已写入" : "未启用");
        return new IndexStats(repoId, collected.files(), collected.chunks().size(), vectorized);
    }

    // ------------------------------------------------------------------
    // 采集
    // ------------------------------------------------------------------

    private Collected collectChunks(Path root) {
        List<Path> javaFiles;
        try {
            javaFiles = SourceFiles.listJavaFiles(root, MAX_FILES);
        } catch (IOException e) {
            throw new IllegalStateException("遍历工程失败: " + root + " —— " + e.getMessage(), e);
        }
        if (javaFiles.size() >= MAX_FILES) {
            log.warn("源码文件数达到上限 {}，其余文件本次未索引（工程根: {}）", MAX_FILES, root);
        }

        List<CodeChunk> chunks = new ArrayList<>();
        int files = 0;
        for (Path file : javaFiles) {
            files++;
            String relative = SourceFiles.relativePath(root, file);
            try {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                chunks.addAll(CodeChunker.chunk(relative, source));
            } catch (Exception e) {
                // 一个坏文件不该让整次索引失败：跳过并留痕，继续处理其余文件
                log.warn("跳过无法切块的文件 {}: {}", relative, e.getMessage());
            }
        }
        return new Collected(chunks, files);
    }

    // ------------------------------------------------------------------
    // 向量化
    // ------------------------------------------------------------------

    /**
     * 给每个块算向量。
     *
     * @return 与块一一对应的向量；向量路不可用或中途失败时返回 {@code null}（表示不写向量）
     */
    private List<float[]> embedAll(List<CodeChunk> chunks) {
        if (!embeddingProvider.available() || chunks.isEmpty()) {
            return null;
        }
        List<float[]> vectors = new ArrayList<>(chunks.size());
        try {
            for (CodeChunk chunk : chunks) {
                String input = chunk.content();
                if (input.length() > MAX_EMBEDDING_CHARS) {
                    input = input.substring(0, MAX_EMBEDDING_CHARS);
                }
                vectors.add(embeddingProvider.embed(input));
            }
            return vectors;
        } catch (Exception e) {
            // 降级为「无向量」而不是让整次索引失败：全文/符号两路仍可用，且 vectorized=false 会如实上报
            log.warn("向量化失败，本次索引不写向量（检索退化为全文 + 符号两路）: {}", e.getMessage());
            return null;
        }
    }

    private record Collected(List<CodeChunk> chunks, int files) {
    }
}
