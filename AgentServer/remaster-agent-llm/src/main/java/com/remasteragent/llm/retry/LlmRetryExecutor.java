package com.remasteragent.llm.retry;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 大模型调用的重试执行器 —— 把 SDK 的静默重试换成「可见、可退避、有预算」的自研重试。
 *
 * <h2>为什么不用 LangChain4j 的 maxRetries</h2>
 * <p>三个理由，按重要性排序：
 * <ol>
 *   <li><b>不可见。</b>SDK 重试只打一行 WARN，节点层拿不到通知，于是没法把「正在重试」
 *       告诉前端。用户看到的是一个 RUNNING 节点长时间没有变化 —— 与「卡死」无法区分。</li>
 *   <li><b>不可控。</b>它对所有异常一视同仁地重试，包括 400（prompt 超长）、401（Key 错）。
 *       这类错误重试一百次也是同样的结果，只是在烧钱。</li>
 *   <li><b>没有总预算。</b>「单次超时 × (1 + 重试次数)」就是实际耗时上限，配成
 *       180s × 3 = 9 分钟时，一个节点没有任何机制能提前止损。</li>
 * </ol>
 *
 * <p>于是这里的做法是：把 SDK 的 {@code maxRetries} 设为 0（{@code LlmConfig} 里做），
 * 由本类统一接管重试，并在每次尝试失败后回调 {@link LlmAttemptListener}。
 *
 * <h2>异常分类为什么直接复用 LangChain4j 的层级</h2>
 * <p>它自己已经把 HTTP 状态码映射好了：5xx 落到 {@link RetriableException} 体系
 * （{@code InternalServerException} / {@code RateLimitException} / {@code TimeoutException}），
 * 4xx 落到 {@link NonRetriableException} 体系（鉴权、参数、模型不存在）。
 * 自己再维护一张状态码表只会和它慢慢走偏，所以这里优先认这两个基类，
 * 只有「没被映射过的原始 {@link HttpException}」才回退到按状态码判断。
 *
 * <h2>为什么要留调用级总预算</h2>
 * <p>重试上限与超时相乘就是最坏耗时。加预算后，「最坏耗时」从一个模糊的乘积变成一个
 * 明确的数字，且是<b>可配的</b>。判定用的是「已经花掉的 + 上一次尝试的耗时」——
 * 因为单次请求要一两分钟，剩余预算只够半次尝试时再发起一次，就是在明知故犯地超支。
 *
 * <p>本类不依赖 Spring，所有外部不可控因素（时钟、睡眠）都可注入，因此重试次数、
 * 退避序列、预算截断这些行为全都能在毫秒级单测里钉死。
 */
public final class LlmRetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(LlmRetryExecutor.class);

    /** 沿异常链向下找原因的深度上限 —— 防病态的自引用 cause 造成死循环。 */
    private static final int MAX_CAUSE_DEPTH = 8;

    /** 退避上限。再长就是在拿用户的时间赌上游恢复，不如让节点尽快失败、走 attempt+1。 */
    private static final long MAX_BACKOFF_MILLIS = 30_000L;

    /** 可注入的睡眠，便于单测跳过真实等待。 */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;

        Sleeper REAL = Thread::sleep;
        Sleeper NONE = millis -> {
        };
    }

    private final int maxAttempts;
    private final long backoffBaseMillis;
    private final long budgetMillis;
    private final Sleeper sleeper;
    private final LongSupplier clock;

    public LlmRetryExecutor(int maxRetries, long backoffBaseMillis, long budgetSeconds) {
        this(maxRetries, backoffBaseMillis, budgetSeconds, Sleeper.REAL, System::currentTimeMillis);
    }

    /**
     * 全参构造 —— 供单测注入假时钟与空睡眠。
     *
     * @param maxRetries        失败后最多再试几次；0 表示不重试（只调一次）
     * @param backoffBaseMillis 退避基准，第 n 次失败后等 {@code base × 2^(n-1)}（封顶 {@value #MAX_BACKOFF_MILLIS}ms）
     * @param budgetSeconds     单次调用（含全部重试）的总时间预算；{@code <=0} 表示不限制
     */
    public LlmRetryExecutor(int maxRetries, long backoffBaseMillis, long budgetSeconds,
                            Sleeper sleeper, LongSupplier clock) {
        this.maxAttempts = Math.max(1, maxRetries + 1);
        this.backoffBaseMillis = Math.max(0L, backoffBaseMillis);
        this.budgetMillis = budgetSeconds <= 0 ? 0L : budgetSeconds * 1000L;
        this.sleeper = sleeper == null ? Sleeper.NONE : sleeper;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** 不重试、不等待的执行器 —— 「只想直调一次」的场景（含大多数单测）。 */
    public static LlmRetryExecutor noRetry() {
        return new LlmRetryExecutor(0, 0, 0, Sleeper.NONE, System::currentTimeMillis);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** 总预算毫秒数；0 表示未设限。 */
    public long budgetMillis() {
        return budgetMillis;
    }

    /**
     * 执行一次带重试的调用。
     *
     * @param label    日志里标识这次调用（如「改写 src/main/.../Demo.java」）
     * @param call     实际调用体，通常是 {@code () -> model.chat(messages)}
     * @param listener 每次尝试失败后的回调；可为 {@code null}
     * @return 调用体的返回值
     * @throws RuntimeException 不可重试、超出重试上限、或超出总预算时，原样抛出最后一次的异常
     */
    public <T> T execute(String label, Supplier<T> call, LlmAttemptListener listener) {
        LlmAttemptListener sink = listener == null ? LlmAttemptListener.NONE : listener;
        long startedAt = clock.getAsLong();

        for (int attempt = 1; ; attempt++) {
            long attemptStartedAt = clock.getAsLong();
            try {
                T value = call.get();
                if (attempt > 1) {
                    log.info("{} 第 {} 次尝试成功（累计耗时 {}ms）", label, attempt, clock.getAsLong() - startedAt);
                }
                return value;
            } catch (RuntimeException failure) {
                long attemptMs = clock.getAsLong() - attemptStartedAt;
                long elapsedMs = clock.getAsLong() - startedAt;
                String reason = describeShort(failure);

                if (!isRetryable(failure)) {
                    log.warn("{} 第 {}/{} 次尝试失败且不可重试（{}），直接放弃", label, attempt, maxAttempts, reason);
                    sink.onAttemptFailed(attempt, maxAttempts, failure, false);
                    throw failure;
                }
                if (attempt >= maxAttempts) {
                    log.warn("{} 第 {}/{} 次尝试失败（{}），已达重试上限", label, attempt, maxAttempts, reason);
                    sink.onAttemptFailed(attempt, maxAttempts, failure, false);
                    throw failure;
                }
                if (budgetWouldBeExceeded(elapsedMs, attemptMs)) {
                    log.warn("{} 第 {}/{} 次尝试失败（{}）；已耗时 {}ms，再试一次预计超过 {}ms 总预算，不再重试",
                            label, attempt, maxAttempts, reason, elapsedMs, budgetMillis);
                    sink.onAttemptFailed(attempt, maxAttempts, failure, false);
                    throw failure;
                }

                long waitMs = backoffMillis(attempt);
                log.warn("{} 第 {}/{} 次尝试失败（{}），本次耗时 {}ms，{}ms 后重试",
                        label, attempt, maxAttempts, reason, attemptMs, waitMs);
                sink.onAttemptFailed(attempt, maxAttempts, failure, true);
                sleepQuietly(waitMs);
            }
        }
    }

    /**
     * 再试一次会不会把总预算撑爆 —— 用「上一次的耗时」当这次的预估。
     *
     * <p>拿上一次耗时做预估而不是取配置里的超时值，是因为实测单次耗时波动很大
     * （同一份 prompt 从 50s 到 130s 都出现过），用真实观测值比用理论上限更贴近现实。
     */
    private boolean budgetWouldBeExceeded(long elapsedMs, long lastAttemptMs) {
        return budgetMillis > 0 && elapsedMs + lastAttemptMs > budgetMillis;
    }

    /** 第 n 次失败后等 {@code base × 2^(n-1)}，指数项封顶到 16 倍，总量再封顶到 {@value #MAX_BACKOFF_MILLIS}ms。 */
    private long backoffMillis(int attempt) {
        long wait = backoffBaseMillis << Math.min(attempt - 1, 4);
        return Math.min(wait, MAX_BACKOFF_MILLIS);
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            // 吞掉中断会让关停流程卡住，所以恢复中断标记后向上抛
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待大模型重试时线程被中断", e);
        }
    }

    /**
     * 这个异常值不值得再试一次。
     *
     * <p>先认 LangChain4j 的语义基类，认不出来再按原始状态码判，最后才看网络异常类型。
     * 找不到任何依据时<b>返回 false</b>：未知异常重试三次，等于用三倍的时间去确认同一个未知数。
     */
    public static boolean isRetryable(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            if (current instanceof NonRetriableException) {
                return false;
            }
            if (current instanceof RetriableException) {
                return true;
            }
            if (current instanceof HttpException http) {
                return isRetryableStatus(http.statusCode());
            }
            if (current instanceof UnknownHostException) {
                return false;
            }
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof SocketException
                    || current instanceof InterruptedIOException
                    || current instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /** 408 / 429 / 5xx 可重试；其余 4xx 重试没有意义（同样的请求只会得到同样的拒绝）。 */
    public static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    /**
     * 给人类看的一句话失败原因。
     *
     * <p>它会被拼进进度事件，最终显示在页面上，所以不能只塞 {@code e.toString()}：
     * 用户需要一眼看出「是上游超时，不是我的代码有问题」。
     */
    public static String describeShort(Throwable failure) {
        if (failure == null) {
            return "未知原因";
        }
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            if (current instanceof HttpException http) {
                return "HTTP " + http.statusCode() + "（" + statusLabel(http.statusCode()) + "）";
            }
            if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException) {
                return "请求超时";
            }
            if (current instanceof UnknownHostException) {
                return "域名解析失败";
            }
            if (current instanceof ConnectException) {
                return "连接被拒绝";
            }
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return failure.getClass().getSimpleName() + ": " + abbreviate(message, 120);
    }

    private static String statusLabel(int code) {
        return switch (code) {
            case 400 -> "请求不被接受";
            case 401 -> "鉴权失败";
            case 403 -> "无权限";
            case 404 -> "模型不存在";
            case 408 -> "请求超时";
            case 413 -> "请求体过大";
            case 422 -> "参数不合法";
            case 429 -> "被限流";
            case 500 -> "服务端错误";
            case 502 -> "网关错误";
            case 503 -> "服务不可用";
            case 504 -> "网关超时";
            case 524 -> "上游响应超时";
            default -> code >= 500 ? "服务端错误" : "请求被拒绝";
        };
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
