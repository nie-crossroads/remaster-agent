package com.remasteragent.tools.probe;

import com.remasteragent.tools.config.DotEnv;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 环境探针 —— 在写业务代码之前，先把所有外部依赖验一遍。
 *
 * <p>存在的意义：这个项目的失败模式里，最浪费时间的一种是「代码写完了才发现
 * 某个服务连不上，然后开始怀疑是自己的代码有问题」。所以先跑这个，
 * 让每个前提都有明确的 PASS / FAIL。
 *
 * <p>刻意不依赖 Spring 与任何第三方客户端：PostgreSQL 用 JDBC，
 * Redis 用原生 RESP 内联命令走 Socket，大模型用 JDK 自带的 HttpClient。
 * 这样探针本身不会因为「框架没配好」而失败，它只回答「网络与服务是否可用」。
 *
 * <p>运行：
 * <pre>
 * mvn.cmd -pl remaster-agent-tools exec:java -Dexec.mainClass=com.remasteragent.tools.probe.EnvProbe
 * </pre>
 */
public final class EnvProbe {

    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RESET = "\u001B[0m";

    private static final List<String> FAILURES = new ArrayList<>();
    private static final List<String> WARNINGS = new ArrayList<>();

    private EnvProbe() {
    }

    public static void main(String[] args) {
        Map<String, String> env = DotEnv.load();
        System.out.println("=".repeat(72));
        System.out.println(" RemasterAgent 环境探针");
        System.out.println("=".repeat(72));

        // 向量扩展现在装在业务库里（单库设计），业务库探针也要校验 vector 可用
        probePostgres("业务库（含向量扩展）", env,
                req(env, "POSTGRES_HOST"), intOr(env, "POSTGRES_PORT", 5432),
                req(env, "POSTGRES_DB"), req(env, "POSTGRES_USER"), req(env, "POSTGRES_PASSWORD"),
                true);

        probeRedis(env, req(env, "REDIS_HOST"), intOr(env, "REDIS_PORT", 6379),
                req(env, "REDIS_PASSWORD"));

        probeLlm(env, req(env, "LLM_BASE_URL"), req(env, "LLM_API_KEY"), req(env, "LLM_MODEL"));

        System.out.println();
        System.out.println("=".repeat(72));
        if (FAILURES.isEmpty() && WARNINGS.isEmpty()) {
            System.out.println(GREEN + " 全部通过，可以开始写业务代码" + RESET);
        } else {
            for (String w : WARNINGS) {
                System.out.println(YELLOW + " [警告] " + w + RESET);
            }
            for (String f : FAILURES) {
                System.out.println(RED + " [失败] " + f + RESET);
            }
        }
        System.out.println("=".repeat(72));

        // 有硬失败时用非 0 退出码，方便脚本判断
        if (!FAILURES.isEmpty()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // PostgreSQL
    // ------------------------------------------------------------------

    private static void probePostgres(String label, Map<String, String> env,
                                      String host, int port, String db,
                                      String user, String password, boolean needVector) {
        System.out.println();
        System.out.printf("--- %s  %s:%d/%s  (user=%s, password=%s)%n",
                label, host, port, db, user, mask(password));

        // 连维护库 postgres 而不是目标库：目标库可能还没建，这本来就是我们要检查的事
        String url = "jdbc:postgresql://" + host + ":" + port + "/postgres";
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            String version = queryOne(conn, "select version()");
            System.out.println(GREEN + " [通过] 连接与鉴权" + RESET);
            System.out.println("        " + abbreviate(version, 90));

            boolean dbExists = exists(conn, "select 1 from pg_database where datname = ?", db);
            if (dbExists) {
                System.out.println(GREEN + " [通过] 目标库 " + db + " 已存在" + RESET);
            } else {
                System.out.println(YELLOW + " [待建] 目标库 " + db + " 尚不存在（由建库引导创建）" + RESET);
            }

            boolean extAvailable = exists(conn,
                    "select 1 from pg_available_extensions where name = 'vector'", null);

            // 扩展是「按库」安装的：V2 迁移把 vector / pg_trgm 装进目标库（remaster），
            // 不是维护库 postgres。所以连目标库去查真正装没装，否则会误报「尚未 CREATE EXTENSION」。
            // 目标库走公网时连接可能偶发抖动，这里带重试，避免一次性失败误报。
            boolean vectorInstalled = false;
            boolean trgmInstalled = false;
            boolean targetCheckOk = false;
            if (dbExists) {
                String targetUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db;
                Exception lastErr = null;
                for (int attempt = 1; attempt <= 3 && !targetCheckOk; attempt++) {
                    try (Connection tconn = DriverManager.getConnection(targetUrl, user, password)) {
                        vectorInstalled = exists(tconn, "select 1 from pg_extension where extname = 'vector'", null);
                        trgmInstalled = exists(tconn, "select 1 from pg_extension where extname = 'pg_trgm'", null);
                        targetCheckOk = true;
                    } catch (Exception e) {
                        lastErr = e;
                    }
                }
                if (!targetCheckOk && lastErr != null) {
                    WARNINGS.add(label + " 连目标库查扩展失败（已重试 3 次）: " + rootMessage(lastErr));
                }
            }

            if (targetCheckOk && vectorInstalled) {
                System.out.println(GREEN + " [通过] vector 扩展已安装（在 " + db + "）" + RESET);
            } else if (!targetCheckOk) {
                System.out.println(YELLOW + " [警告] 未能连上 " + db + " 核查扩展，请手动确认 vector 已安装" + RESET);
            } else if (extAvailable) {
                System.out.println(YELLOW + " [警告] vector 扩展在 " + db + " 尚未安装（V2 迁移会创建）" + RESET);
            } else if (needVector) {
                FAILURES.add(label + " 上找不到 vector 扩展，代码 RAG 无法落地");
                System.out.println(RED + " [失败] 该实例没有 pgvector 可用" + RESET);
            } else {
                System.out.println("        vector 扩展不可用（业务库不需要，可忽略）");
            }
            if (targetCheckOk && vectorInstalled && !trgmInstalled) {
                WARNINGS.add(label + " 上 pg_trgm 未安装，符号检索索引建不了");
                System.out.println(YELLOW + " [警告] pg_trgm 扩展未安装（符号检索索引依赖它）" + RESET);
            }
        } catch (Exception e) {
            FAILURES.add(label + " 连接失败: " + rootMessage(e));
            System.out.println(RED + " [失败] " + abbreviate(rootMessage(e), 140) + RESET);
        }
    }

    private static String queryOne(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "";
        }
    }

    private static boolean exists(Connection conn, String sql, String param) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (param != null) {
                ps.setString(1, param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ------------------------------------------------------------------
    // Redis：用 RESP 内联命令直接走 Socket，不引任何客户端
    // ------------------------------------------------------------------

    private static void probeRedis(Map<String, String> env, String host, int port, String password) {
        System.out.println();
        System.out.printf("--- Redis  %s:%d  (password=%s)%n", host, port, mask(password));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            if (password != null && !password.isBlank()) {
                out.write(("AUTH " + password + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                String authReply = in.readLine();
                if (authReply == null || !authReply.startsWith("+OK")) {
                    FAILURES.add("Redis 鉴权失败，回复: " + abbreviate(authReply, 80));
                    System.out.println(RED + " [失败] 鉴权失败: " + abbreviate(authReply, 80) + RESET);
                    return;
                }
                System.out.println(GREEN + " [通过] 鉴权" + RESET);
            }

            out.write("PING\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            String pong = in.readLine();
            if (pong != null && pong.startsWith("+PONG")) {
                System.out.println(GREEN + " [通过] PING → PONG" + RESET);
            } else {
                FAILURES.add("Redis PING 异常回复: " + abbreviate(pong, 80));
                System.out.println(RED + " [失败] PING 回复异常: " + abbreviate(pong, 80) + RESET);
            }

            // Stream 是本项目的任务队列，顺手确认版本支持（Redis 5.0+ 才有 Stream）
            out.write("INFO server\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("redis_version")) {
                    System.out.println("        " + line.trim());
                    break;
                }
                if (line.startsWith("-")) {
                    break;
                }
            }
        } catch (Exception e) {
            FAILURES.add("Redis 连接失败: " + rootMessage(e));
            System.out.println(RED + " [失败] " + abbreviate(rootMessage(e), 140) + RESET);
        }
    }

    // ------------------------------------------------------------------
    // 大模型：先列模型，确认目标模型真的存在；再发一条最小对话验鉴权
    // ------------------------------------------------------------------

    private static void probeLlm(Map<String, String> env, String baseUrl, String apiKey, String model) {
        System.out.println();
        System.out.printf("--- 大模型  %s  (model=%s, key=%s)%n", baseUrl, model, mask(apiKey));

        // 这个网关走 Cloudflare，实测首次连接可能接近 10 秒，超时不能设太紧；
        // 同时强制 HTTP/1.1 —— 部分中转网关对 HTTP/2 的兼容性不稳。
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

        // 1) 列出可用模型 —— 这一步能把「鉴权错」和「模型名不存在」区分开
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(root + "/models"))
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                System.out.println(GREEN + " [通过] GET /models 返回 200" + RESET);
                List<String> models = extractModelIds(body);
                if (models.isEmpty()) {
                    WARNINGS.add("模型列表解析为空，可能返回结构与 OpenAI 不完全一致");
                    System.out.println("        " + abbreviate(body, 200));
                } else {
                    System.out.println("        可用模型 " + models.size() + " 个"
                            + (models.size() > 12 ? "，前 12 个：" : "："));
                    models.stream().limit(12).forEach(m -> System.out.println("          - " + m));
                    if (models.contains(model)) {
                        System.out.println(GREEN + " [通过] 目标模型 " + model + " 在列表中" + RESET);
                    } else {
                        FAILURES.add("目标模型 " + model + " 不在可用列表中，需要换模型名");
                        System.out.println(RED + " [失败] 目标模型 " + model + " 不在列表中" + RESET);
                    }
                }
            } else {
                FAILURES.add("GET /models 返回 " + response.statusCode() + " : " + abbreviate(response.body(), 120));
                System.out.println(RED + " [失败] HTTP " + response.statusCode() + " → "
                        + abbreviate(response.body(), 140) + RESET);
            }
        } catch (Exception e) {
            FAILURES.add("调用 /models 失败: " + rootMessage(e));
            System.out.println(RED + " [失败] " + abbreviate(rootMessage(e), 140) + RESET);
        }

        // 2) 最小对话请求 —— 确认 chat/completions 真的能出结果，而不只是列表能拉
        try {
            String payload = """
                    {"model":"%s","messages":[{"role":"user","content":"reply with the single word: pong"}],"max_tokens":16}
                    """.formatted(model);
            HttpRequest request = HttpRequest.newBuilder(URI.create(root + "/chat/completions"))
                    .timeout(Duration.ofSeconds(240))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            long start = System.currentTimeMillis();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;

            if (response.statusCode() == 200) {
                System.out.println(GREEN + " [通过] POST /chat/completions 返回 200（" + cost + " ms）" + RESET);
                System.out.println("        响应片段: " + abbreviate(response.body(), 220));
            } else {
                FAILURES.add("POST /chat/completions 返回 " + response.statusCode());
                System.out.println(RED + " [失败] HTTP " + response.statusCode() + " → "
                        + abbreviate(response.body(), 160) + RESET);
            }
        } catch (Exception e) {
            FAILURES.add("调用 /chat/completions 失败: " + rootMessage(e));
            System.out.println(RED + " [失败] " + abbreviate(rootMessage(e), 140) + RESET);
        }
    }

    /** 从 OpenAI 风格的 {"data":[{"id":"..."}]} 里抠出模型 id，不引 Jackson。 */
    private static List<String> extractModelIds(String body) {
        List<String> ids = new ArrayList<>();
        int idx = 0;
        while (true) {
            int key = body.indexOf("\"id\"", idx);
            if (key < 0) {
                break;
            }
            int colon = body.indexOf(':', key);
            int quoteStart = colon < 0 ? -1 : body.indexOf('"', colon);
            int quoteEnd = quoteStart < 0 ? -1 : body.indexOf('"', quoteStart + 1);
            if (quoteEnd > quoteStart) {
                ids.add(body.substring(quoteStart + 1, quoteEnd));
                idx = quoteEnd + 1;
            } else {
                break;
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static String req(Map<String, String> env, String key) {
        String value = DotEnv.get(env, key);
        if (value == null || value.isBlank()) {
            FAILURES.add("缺少配置项 " + key + "（.env 或环境变量里都没有）");
            return "";
        }
        return value;
    }

    private static int intOr(Map<String, String> env, String key, int defaultValue) {
        return DotEnv.getInt(env, key, defaultValue);
    }

    /** 输出里只显示口令的首尾各两位，避免把凭据打进日志或终端历史。 */
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
