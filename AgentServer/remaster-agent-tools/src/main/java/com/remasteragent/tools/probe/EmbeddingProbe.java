package com.remasteragent.tools.probe;

import com.remasteragent.tools.config.DotEnv;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量模型探针 —— 在依赖向量检索之前，先把「模型能用」和「维度对得上」两件事验掉。
 *
 * <h2>为什么必须单独有一个探针</h2>
 * <p>向量路是这个项目里<b>最容易静默降级</b>的一路：{@code CodeIndexer} 刻意设计成
 * 「向量化失败就写无向量、不中断索引」，{@code HybridRetriever} 又会「向量路不可用就跳过」。
 * 这两处宽容是对的（一个坏模型不该让整个任务失败），但副作用是
 * <b>配置写错时不会有任何报错，只会表现为「检索质量一般」</b>。
 *
 * <p>更隐蔽的一种：模型返回的维度与 {@code code_chunk.embedding vector(1024)} 不一致时，
 * 写入会被 PostgreSQL 直接拒绝 —— 而这发生在索引阶段，日志里只有一行 insert 失败。
 * 所以这里把「返回多少维」和「表要求多少维」摆在一起比。
 *
 * <h2>为什么 chat 与 embedding 的网关/Key 要分开取</h2>
 * <p>两者常常不在同一个网关（本项目的 chat 走中转网关，向量走云厂商 MaaS）。
 * 探针按 {@code LLM_EMBEDDING_BASE_URL/API_KEY} → {@code LLM_BASE_URL/API_KEY} 的顺序回落，
 * 与 {@code LlmProperties} 里的规则完全一致 —— 探针和生产用同一套回落，
 * 否则会出现「探针通过、跑起来失败」这种最浪费时间的结果。
 *
 * <p>运行：
 * <pre>
 * mvn.cmd -o -q -pl remaster-agent-tools exec:java -Dexec.mainClass=com.remasteragent.tools.probe.EmbeddingProbe
 * </pre>
 * 未配置 {@code LLM_EMBEDDING_MODEL} 时不算失败（向量路禁用是合法状态），退出码 0。
 */
public final class EmbeddingProbe {

    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RESET = "\u001B[0m";

    /** 用一段真实的 Java 代码当探针输入：模型对代码的响应才是我们真正关心的分布。 */
    private static final String SAMPLE_TEXT = """
            public String formatDate(Date date) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                return sdf.format(date);
            }
            """;

    private static final List<String> FAILURES = new ArrayList<>();
    private static final List<String> WARNINGS = new ArrayList<>();

    private EmbeddingProbe() {
    }

    public static void main(String[] args) {
        Map<String, String> env = DotEnv.load();
        System.out.println("=".repeat(72));
        System.out.println(" RemasterAgent 向量模型探针");
        System.out.println("=".repeat(72));

        String model = DotEnv.get(env, "LLM_EMBEDDING_MODEL");
        if (model == null || model.isBlank()) {
            System.out.println();
            System.out.println(YELLOW + " 未配置 LLM_EMBEDDING_MODEL —— 向量路处于禁用状态。" + RESET);
            System.out.println(" 这是合法配置：混合检索会退化为「全文 + 符号」两路，任务照常执行。");
            System.out.println(" 要启用向量路，请在 .env 里填 LLM_EMBEDDING_MODEL / _BASE_URL / _API_KEY。");
            System.out.println("=".repeat(72));
            return;
        }

        String baseUrl = firstNonBlank(
                DotEnv.get(env, "LLM_EMBEDDING_BASE_URL"),
                DotEnv.get(env, "LLM_BASE_URL"));
        String apiKey = firstNonBlank(
                DotEnv.get(env, "LLM_EMBEDDING_API_KEY"),
                DotEnv.get(env, "LLM_API_KEY"));
        int configuredDimensions = DotEnv.getInt(env, "LLM_EMBEDDING_DIMENSIONS", 0);

        System.out.println();
        System.out.printf("--- 向量模型  model=%s, dimensions=%s%n",
                model, configuredDimensions > 0 ? String.valueOf(configuredDimensions) : "未指定");
        System.out.printf("    网关  %s%n", baseUrl == null ? "<空>" : baseUrl);
        System.out.printf("    Key   %s%n", mask(apiKey));
        if (DotEnv.get(env, "LLM_EMBEDDING_BASE_URL") == null) {
            System.out.println(YELLOW + "    （未配专用网关，回落到 LLM_BASE_URL —— "
                    + "若该网关不提供 /embeddings，这里会直接失败）" + RESET);
        }

        float[] vector = probeEmbeddingCall(baseUrl, apiKey, model, configuredDimensions);
        if (vector != null) {
            probeDatabaseCompatibility(env, vector.length);
            probeIndexedVectors(env);
        }

        System.out.println();
        System.out.println("=".repeat(72));
        if (FAILURES.isEmpty() && WARNINGS.isEmpty()) {
            System.out.println(GREEN + " 向量路可用：模型可调、维度与向量表一致" + RESET);
        } else {
            for (String w : WARNINGS) {
                System.out.println(YELLOW + " [警告] " + w + RESET);
            }
            for (String f : FAILURES) {
                System.out.println(RED + " [失败] " + f + RESET);
            }
        }
        System.out.println("=".repeat(72));

        if (!FAILURES.isEmpty()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 1) 真的发一次 /embeddings
    // ------------------------------------------------------------------

    private static float[] probeEmbeddingCall(String baseUrl, String apiKey, String model,
                                             int configuredDimensions) {
        if (baseUrl == null || baseUrl.isBlank()) {
            FAILURES.add("网关地址为空（LLM_EMBEDDING_BASE_URL 与 LLM_BASE_URL 都没配）");
            System.out.println(RED + " [失败] 网关地址为空" + RESET);
            return null;
        }
        if (apiKey == null || apiKey.isBlank()) {
            FAILURES.add("API Key 为空（LLM_EMBEDDING_API_KEY 与 LLM_API_KEY 都没配）");
            System.out.println(RED + " [失败] API Key 为空" + RESET);
            return null;
        }

        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        String payload = "{\"model\":\"" + model + "\",\"input\":"
                + quote(SAMPLE_TEXT) + "}";

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(root + "/embeddings"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;

            if (response.statusCode() / 100 != 2) {
                FAILURES.add("POST /embeddings 返回 HTTP " + response.statusCode());
                System.out.println(RED + " [失败] HTTP " + response.statusCode() + " → "
                        + abbreviate(response.body(), 240) + RESET);
                System.out.println("        常见原因：模型名不对、Key 无该模型权限、"
                        + "或该网关压根没有 /embeddings 接口（chat 网关常见）");
                return null;
            }

            System.out.println(GREEN + " [通过] POST /embeddings 返回 200（" + cost + " ms）" + RESET);

            float[] vector = extractVector(response.body());
            if (vector == null) {
                FAILURES.add("/embeddings 响应里没有向量数组");
                System.out.println(RED + " [失败] 响应里找不到 data[0].embedding → "
                        + abbreviate(response.body(), 200) + RESET);
                return null;
            }

            System.out.println("        实际维度 " + vector.length);
            System.out.printf("        向量前 3 维 [%.6f, %.6f, %.6f]%n",
                    vector[0], vector.length > 1 ? vector[1] : 0f, vector.length > 2 ? vector[2] : 0f);

            String usage = extractField(response.body(), "usage");
            if (usage != null) {
                System.out.println("        usage " + abbreviate(usage, 120));
            }

            if (configuredDimensions > 0 && vector.length != configuredDimensions) {
                FAILURES.add("实际维度 " + vector.length + " 与 LLM_EMBEDDING_DIMENSIONS="
                        + configuredDimensions + " 不一致");
                System.out.println(RED + " [失败] 实际维度与配置的 " + configuredDimensions
                        + " 不一致（请求里带的 dimensions 没被模型接受？）" + RESET);
            } else {
                System.out.println(GREEN + " [通过] 维度与配置一致" + RESET);
            }
            return vector;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FAILURES.add("embedding 调用被中断");
            System.out.println(RED + " [失败] 调用被中断" + RESET);
            return null;
        } catch (Exception e) {
            FAILURES.add("调用 /embeddings 失败: " + rootMessage(e));
            System.out.println(RED + " [失败] " + abbreviate(rootMessage(e), 200) + RESET);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 2) 维度必须对得上 code_chunk.embedding 的 vector(1024)
    // ------------------------------------------------------------------

    private static void probeDatabaseCompatibility(Map<String, String> env, int actualDimensions) {
        System.out.println();
        System.out.println("--- 与向量表的兼容性");

        String host = DotEnv.get(env, "POSTGRES_HOST");
        int port = DotEnv.getInt(env, "POSTGRES_PORT", 5432);
        String db = DotEnv.get(env, "POSTGRES_DB");
        String user = DotEnv.get(env, "POSTGRES_USER");
        String password = DotEnv.get(env, "POSTGRES_PASSWORD");

        if (host == null || db == null || user == null) {
            WARNINGS.add("缺少 PostgreSQL 配置，跳过向量表维度核对");
            System.out.println(YELLOW + " [警告] 缺少 PostgreSQL 配置，跳过核对" + RESET);
            return;
        }

        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            String declared = declaredVectorType(conn);
            if (declared == null) {
                WARNINGS.add("查不到 code_chunk.embedding 的列类型（表还没建？先跑 DbMigrate）");
                System.out.println(YELLOW + " [警告] 查不到 code_chunk.embedding 列类型" + RESET);
                return;
            }
            System.out.println("        列类型 code_chunk.embedding = " + declared);

            int declaredDimensions = parseDimensions(declared);
            if (declaredDimensions == actualDimensions) {
                System.out.println(GREEN + " [通过] 模型维度与列维度一致（" + actualDimensions + "）" + RESET);
            } else {
                FAILURES.add("模型维度 " + actualDimensions + " 与列 " + declared
                        + " 不一致，写入会被拒绝");
                System.out.println(RED + " [失败] 维度不一致：模型 " + actualDimensions
                        + " vs 列 " + declared + RESET);
            }

            // 干跑一次 CAST：确认这个向量串真能被 pgvector 接受，而不是只比数字
            try (PreparedStatement ps = conn.prepareStatement(
                    "select vector_dims(cast(? as vector))")) {
                ps.setString(1, toLiteral(actualDimensions));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == actualDimensions) {
                        System.out.println(GREEN + " [通过] pgvector 能解析 " + actualDimensions
                                + " 维向量字面量" + RESET);
                    } else {
                        WARNINGS.add("pgvector 解析 " + actualDimensions + " 维字面量结果异常");
                    }
                }
            } catch (Exception e) {
                // vector_dims 需要 pgvector 0.7+；拿不到就只提示，不当失败
                WARNINGS.add("pgvector 字面量解析干跑跳过: " + rootMessage(e));
                System.out.println(YELLOW + " [警告] 跳过字面量干跑：" + abbreviate(rootMessage(e), 100) + RESET);
            }
        } catch (Exception e) {
            WARNINGS.add("连库核对维度失败: " + rootMessage(e));
            System.out.println(YELLOW + " [警告] 连库核对失败：" + abbreviate(rootMessage(e), 140) + RESET);
        }
    }

    /**
     * 库里到底落了多少向量 —— **按仓库分开数**。
     *
     * <h2>为什么光有「模型能调通」还不够</h2>
     * <p>{@code CodeIndexer} 刻意设计成「向量化失败就写无向量、不中断索引」，而
     * {@code HybridRetriever} 又会「向量路不可用就跳过」。两处宽容都是对的，
     * 但合起来的效果是：<b>向量其实没写进去时，一切都「正常」</b>，只是检索质量差一点。
     *
     * <h2>为什么必须按仓库分组</h2>
     * <p>第一版这里直接数全表，结果得出一句吓人的「33/76 个块带向量」——
     * 而真相是另外 43 个块属于**在向量路还没启用时索引的旧仓库**（那时用的是 Noop provider，
     * 本来就不写向量）。全表计数会把「历史遗留」误报成「当前故障」，
     * 这正是探针最不该犯的错：探针说谎比没有探针更糟。
     *
     * <p>所以按 repo 分组，并且**只看「最近一次被索引的仓库」**（判据是块的最新写入时间，
     * 不是 repo.id —— id 是首次索引时分配的，一个老仓库重新索引后 id 依然最小，
     * 拿它当「最新」会得出完全相反的结论，第一版就是这么误判的）。
     * 旧仓库缺向量只给一句提示，并说明怎么补（重跑一次任务）。
     */
    private static void probeIndexedVectors(Map<String, String> env) {
        System.out.println();
        System.out.println("--- 已落库的向量（按仓库）");

        String host = DotEnv.get(env, "POSTGRES_HOST");
        int port = DotEnv.getInt(env, "POSTGRES_PORT", 5432);
        String db = DotEnv.get(env, "POSTGRES_DB");
        String user = DotEnv.get(env, "POSTGRES_USER");
        String password = DotEnv.get(env, "POSTGRES_PASSWORD");

        if (host == null || db == null || user == null) {
            System.out.println(YELLOW + " [警告] 缺少 PostgreSQL 配置，跳过" + RESET);
            return;
        }

        String latestSql = "select repo_id from code_chunk order by created_at desc limit 1";
        String sql = """
                select r.id,
                       r.name,
                       count(c.id)         as total,
                       count(c.embedding)  as vectorized
                from repo r
                left join code_chunk c on c.repo_id = r.id
                group by r.id, r.name
                order by r.id
                """;

        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        try (Connection conn = DriverManager.getConnection(url, user, password)) {

            long latestRepoId = -1;
            try (PreparedStatement ps = conn.prepareStatement(latestSql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    latestRepoId = rs.getLong(1);
                }
            }

            boolean any = false;
            int latestTotal = 0;
            int latestVectorized = 0;

            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    any = true;
                    long repoId = rs.getLong("id");
                    int total = rs.getInt("total");
                    int vectorized = rs.getInt("vectorized");
                    System.out.printf("        repo=%d %-28s 块=%-4d 带向量=%-4d%s%n",
                            repoId, rs.getString("name"), total, vectorized,
                            repoId == latestRepoId ? "  ← 最近一次索引" : "");
                    if (repoId == latestRepoId) {
                        latestTotal = total;
                        latestVectorized = vectorized;
                    }
                }
            }

            if (!any) {
                System.out.println("        还没有索引过任何工程（跑一次任务后重试）");
                return;
            }

            if (latestRepoId < 0 || latestTotal == 0) {
                System.out.println(YELLOW + " [警告] 最近一次索引的仓库里没有分块，索引可能没跑成功" + RESET);
            } else if (latestVectorized == latestTotal) {
                System.out.println(GREEN + " [通过] 最近索引的仓库所有块都带向量 —— 向量路真的在工作" + RESET);
            } else if (latestVectorized == 0) {
                FAILURES.add("最近索引的仓库（repo=" + latestRepoId + "）有 " + latestTotal
                        + " 个块但一个向量都没有 —— 向量路静默降级了");
                System.out.println(RED + " [失败] 最近索引的仓库一个向量都没有：向量路处于静默降级状态" + RESET);
            } else {
                WARNINGS.add("最近索引的仓库只有 " + latestVectorized + "/" + latestTotal
                        + " 个块带向量，部分块的向量化失败了，看索引日志里的 WARN");
                System.out.println(YELLOW + " [警告] 最近索引的仓库部分块缺向量" + RESET);
            }

            System.out.println("        注：旧仓库缺向量通常是因为索引时向量路还没启用（Noop），");
            System.out.println("            重跑一次该工程的任务即可补齐，不需手工修数据。");
        } catch (Exception e) {
            WARNINGS.add("统计 code_chunk 失败: " + rootMessage(e));
            System.out.println(YELLOW + " [警告] 统计失败：" + abbreviate(rootMessage(e), 140) + RESET);
        }
    }

    /** 取列的真实类型文本，例如 {@code vector(1024)}。 */
    private static String declaredVectorType(Connection conn) {
        String sql = """
                select format_type(a.atttypid, a.atttypmod)
                from pg_attribute a
                where a.attrelid = 'code_chunk'::regclass
                  and a.attname = 'embedding'
                  and not a.attisdropped
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 {@code vector(1024)} 里抠出 1024；拿不到就返回 -1。 */
    private static int parseDimensions(String declaredType) {
        int open = declaredType.indexOf('(');
        int close = declaredType.indexOf(')', open + 1);
        if (open < 0 || close < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(declaredType.substring(open + 1, close).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String toLiteral(int dimensions) {
        StringBuilder sb = new StringBuilder(dimensions * 4 + 2);
        sb.append('[');
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(i % 7 == 0 ? "1.0" : "0.0");
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------
    // 小工具：不引 Jackson，够用就好
    // ------------------------------------------------------------------

    /** 从 {@code {"data":[{"embedding":[...]}]}} 里抠出向量。 */
    private static float[] extractVector(String body) {
        int key = body.indexOf("\"embedding\"");
        if (key < 0) {
            return null;
        }
        int open = body.indexOf('[', key);
        int close = body.indexOf(']', open + 1);
        if (open < 0 || close < 0) {
            return null;
        }
        String inner = body.substring(open + 1, close).trim();
        if (inner.isEmpty()) {
            return null;
        }
        String[] parts = inner.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                vector[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return vector;
    }

    /** 抠出一个对象的原文（用于打印 usage），不解析结构。 */
    private static String extractField(String body, String field) {
        int key = body.indexOf("\"" + field + "\"");
        if (key < 0) {
            return null;
        }
        int open = body.indexOf('{', key);
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int i = open; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return body.substring(open, i + 1);
                }
            }
        }
        return null;
    }

    private static String quote(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 16).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return "<空>";
        }
        if (secret.length() <= 6) {
            return "***";
        }
        return secret.substring(0, 2) + "***" + secret.substring(secret.length() - 2)
                + "(len=" + secret.length() + ")";
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
