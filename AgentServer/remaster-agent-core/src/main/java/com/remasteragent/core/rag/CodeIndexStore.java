package com.remasteragent.core.rag;

import com.remasteragent.common.rag.CodeChunk;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码索引的持久化 —— {@code repo} / {@code code_chunk} 两张表的所有读写都收敛在这里。
 *
 * <h2>写入策略：按 repo 整体重建，而不是增量 diff</h2>
 * <p>重新索引一个工程时，先按 {@code repo_id} 删光旧块、再全量插入。这比「比对每块的内容哈希只更新变化的块」
 * 笨，但<b>结果唯一确定、没有中间态</b>：不会出现「旧块没删干净 + 新块已插入」导致的重复召回，
 * 也不需要维护一套「块的身份」概念。索引是一次离线重活（不在任务热路径上），简单可靠比省那几秒重要。
 *
 * <h2>为什么向量用手写字符串而不是 pgvector 的 Java 类型</h2>
 * <p>pgvector 的文本输入格式就是 {@code '[1,2,3]'}。用 {@code CAST(? AS vector)} 传这个字符串，
 * 零新增依赖、SQL 一眼看得懂；引入 {@code com.pgvector.PGvector} 只会多包一层类型转换。
 * 这与「善用 PG 专有能力、不额外加抽象去藏它」的整体取向一致。
 */
@Repository
public class CodeIndexStore {

    private final JdbcTemplate jdbc;

    public CodeIndexStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 仓库
    // ------------------------------------------------------------------

    /**
     * 幂等登记一个被索引的工程，返回 repo_id。
     *
     * <p>靠 V3 建的 {@code uq_repo_root_path} 唯一约束做 upsert：同一个 root_path 永远只有一行，
     * 重复索引不会产生「同一个工程的多份 repo」（那会让检索命中陈旧副本）。
     */
    public long upsertRepo(String rootPath, String name) {
        Long id = jdbc.queryForObject("""
                INSERT INTO repo (name, root_path) VALUES (?, ?)
                ON CONFLICT (root_path) DO UPDATE SET name = EXCLUDED.name
                RETURNING id
                """, Long.class, name, rootPath);
        if (id == null) {
            throw new IllegalStateException("upsert repo 没有返回 id: " + rootPath);
        }
        return id;
    }

    /**
     * 按工程根目录找已登记的 repo id —— 检索侧用。
     *
     * <p>不把 repoId 塞进 checkpoint：那会给 ANALYZE 的 JSON 形状加一个「检索侧的关注点」，
     * 而它本来只该描述「分析结果」。多一次轻量查询换契约稳定，划算。
     */
    public java.util.Optional<Long> findRepoId(String rootPath) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM repo WHERE root_path = ?", Long.class, rootPath);
        return ids.stream().findFirst();
    }

    // ------------------------------------------------------------------
    // 分块
    // ------------------------------------------------------------------

    /** 删除该仓库的全部分块（重建索引的第一步）。 */
    public void deleteChunks(long repoId) {
        jdbc.update("DELETE FROM code_chunk WHERE repo_id = ?", repoId);
    }

    /**
     * 批量插入分块。
     *
     * <p>{@code embeddings} 允许为 {@code null}（表示向量路禁用），此时 embedding 列写 NULL ——
     * 后续向量检索会跳过这些行，而全文/符号两路照常命中。
     *
     * @param chunks     分块列表
     * @param embeddings 与 chunks 一一对应的向量；为 {@code null} 时全部写 NULL
     */
    public void insertChunks(long repoId, List<CodeChunk> chunks, List<float[]> embeddings) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        if (embeddings != null && embeddings.size() != chunks.size()) {
            throw new IllegalArgumentException(
                    "向量数与分块数不一致: " + embeddings.size() + " vs " + chunks.size());
        }

        jdbc.batchUpdate("""
                INSERT INTO code_chunk
                    (repo_id, file_path, symbol, kind, start_line, end_line, content, embedding)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS vector))
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                CodeChunk chunk = chunks.get(i);
                ps.setLong(1, repoId);
                ps.setString(2, chunk.filePath());
                ps.setString(3, chunk.symbol());
                ps.setString(4, chunk.kind());
                ps.setInt(5, chunk.startLine());
                ps.setInt(6, chunk.endLine());
                ps.setString(7, chunk.content());
                float[] vector = embeddings == null ? null : embeddings.get(i);
                ps.setString(8, vector == null ? null : toVectorLiteral(vector));
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
    }

    public int countChunks(long repoId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_chunk WHERE repo_id = ?", Integer.class, repoId);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------
    // 三路检索
    // ------------------------------------------------------------------

    /** 向量路：余弦距离 KNN。没有向量的行（embedding IS NULL）自动被排除。 */
    public List<CodeChunk> searchByVector(long repoId, float[] queryVector, int limit) {
        return jdbc.query("""
                SELECT file_path, symbol, kind, start_line, end_line, content
                  FROM code_chunk
                 WHERE repo_id = ?
                   AND embedding IS NOT NULL
                 ORDER BY embedding <=> CAST(? AS vector)
                 LIMIT ?
                """, CHUNK_MAPPER, repoId, toVectorLiteral(queryVector), limit);
    }

    /**
     * 全文路：tsvector + ts_rank。
     *
     * <p>用 {@code to_tsquery} 而不是 {@code plainto_tsquery}：后者把查询词全部 AND 起来，
     * 而代码检索的查询串是「类名 + 方法名」这种多个标识符的拼接，AND 会几乎召不回东西。
     * 由调用方把词用 {@code |} 连成 OR 表达式传进来（见 {@link QueryTerms#toTsQuery}），
     * 召回更宽，精度交给 RRF 融合去回收。
     */
    public List<CodeChunk> searchByKeyword(long repoId, String tsQuery, int limit) {
        return jdbc.query("""
                SELECT file_path, symbol, kind, start_line, end_line, content
                  FROM code_chunk
                 WHERE repo_id = ?
                   AND tsv @@ to_tsquery('simple', ?)
                 ORDER BY ts_rank(tsv, to_tsquery('simple', ?)) DESC
                 LIMIT ?
                """, CHUNK_MAPPER, repoId, tsQuery, tsQuery, limit);
    }

    /**
     * 符号路：按符号/路径的模糊匹配。
     *
     * <p>用的是 {@code ILIKE}，它能吃到 V2 建的 trgm gin 索引。符号检索是代码 RAG 里最「准」的一路 ——
     * 用户要找 {@code OrderService} 时，符号匹配的确定性远高于语义向量。
     */
    public List<CodeChunk> searchBySymbol(long repoId, String fragment, int limit) {
        String pattern = "%" + fragment + "%";
        return jdbc.query("""
                SELECT file_path, symbol, kind, start_line, end_line, content
                  FROM code_chunk
                 WHERE repo_id = ?
                   AND (symbol ILIKE ? OR file_path ILIKE ?)
                 ORDER BY similarity(COALESCE(symbol, file_path), ?) DESC
                 LIMIT ?
                """, CHUNK_MAPPER, repoId, pattern, pattern, fragment, limit);
    }

    /**
     * 按符号精确取块 —— 依赖图邻居扩展专用。
     *
     * <p>邻居扩展拿到的是一批符号名，这里一次性取回对应分块，避免逐个符号查询（N+1）。
     */
    public List<CodeChunk> findChunksBySymbols(long repoId, List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }
        // 排除无符号的块（文件级），并去重由调用方负责
        List<String> cleaned = new ArrayList<>(symbols.stream()
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .distinct()
                .toList());
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT file_path, symbol, kind, start_line, end_line, content
                  FROM code_chunk
                 WHERE repo_id = ?
                   AND symbol = ANY (CAST(? AS text[]))
                """, CHUNK_MAPPER, repoId, toTextArrayLiteral(cleaned));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static final RowMapper<CodeChunk> CHUNK_MAPPER = (rs, rowNum) -> new CodeChunk(
            rs.getString("file_path"),
            rs.getString("symbol"),
            rs.getString("kind"),
            rs.getInt("start_line"),
            rs.getInt("end_line"),
            rs.getString("content"));

    /** float[] → pgvector 文本字面量 {@code [1.0,2.0]}。 */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /** List<String> → PostgreSQL 数组字面量 {@code {"a","b"}}，元素内双引号转义。 */
    private static String toTextArrayLiteral(List<String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
