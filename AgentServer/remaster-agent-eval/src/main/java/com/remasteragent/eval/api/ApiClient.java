package com.remasteragent.eval.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remasteragent.eval.util.EvalJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 与 API 进程交互的瘦客户端。
 *
 * <p>用 {@code java.net.http} 而不是 {@code RestTemplate} / Feign：harness 只需要两个
 * 端点，为了发两个请求把整个 Spring Web 栈拉进评测模块是不划算的 ——
 * 这也是这个模块能不依赖 core / web 的原因。
 *
 * <p>另一个刻意的选择：<b>全程不用 SSE</b>，只轮询 {@code GET /api/tasks/{id}}。
 * 理由有两条，都不是洁癖：
 * <ol>
 *   <li>harness 要能跑在无浏览器、无长连接的环境里（CI、容器）；</li>
 *   <li>SSE 是内存态 —— API 进程一重启，事件流就断且历史事件必空。
 *       把「等任务结束」这件事押在一个会丢的信号上，评测本身就不稳了。</li>
 * </ol>
 * 轮询的代价只是每 2 秒一条 GET，可以忽略。
 */
public final class ApiClient implements AutoCloseable {

    private final HttpClient http;
    private final String baseUrl;
    private final Duration requestTimeout;

    public ApiClient(String baseUrl, Duration requestTimeout) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 提交一个迁移任务，返回任务 id。
     *
     * @param name 任务名（可选，评测取用例 id）；为 null 时不写入请求体
     * @throws EvalApiException 非 2xx 响应
     */
    public long createTask(String projectRoot, String entryFile, int targetJdk, String name) {
        ObjectNode body = EvalJson.JSON.createObjectNode();
        body.put("projectRoot", projectRoot);
        body.put("entryFile", entryFile);
        body.put("targetJdk", targetJdk);
        if (name != null) {
            body.put("name", name);
        }

        String response = send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build());
        return TaskResponseParser.parse(response).id();
    }

    /** 读取任务当前状态与指标。 */
    public TaskSummary getTask(long taskId) {
        String response = send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks/" + taskId))
                .timeout(requestTimeout)
                .GET()
                .build());
        return TaskResponseParser.parse(response);
    }

    private String send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new EvalApiException("请求 " + request.uri() + " 失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvalApiException("请求 " + request.uri() + " 被中断", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new EvalApiException("请求 " + request.uri() + " 返回 " + response.statusCode()
                    + ": " + abbreviate(response.body()));
        }
        return response.body();
    }

    @Override
    public void close() {
        // HttpClient 不需要显式关闭；实现 AutoCloseable 是为了让调用点能用 try-with-resources
        // 表达「这一批提交用同一个客户端」，而不是散落成若干静态调用。
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String text) {
        return text == null ? "null" : (text.length() <= 300 ? text : text.substring(0, 300) + "…");
    }

    /** API 调用失败 —— 与「任务失败」严格区分：这是 harness 自己出问题了。 */
    public static class EvalApiException extends RuntimeException {

        public EvalApiException(String message) {
            super(message);
        }

        public EvalApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
