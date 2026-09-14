package com.remasteragent.llm.retry;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试执行器的确定性单测 —— 时钟与睡眠都是注入的，所以整个文件跑完是毫秒级，不真的等。
 *
 * <h2>为什么这一层值得钉死</h2>
 * <p>它取代了 LangChain4j 内建的静默重试，而替换掉的正是「不可见」那部分能力 ——
 * 代价是行为复杂度回到了我们自己手上。以下几件事一旦写错，后果都不是「报错」而是更难查的东西：
 * <ul>
 *   <li><b>该重试的没重试</b>：一次偶发 524 直接判任务失败，而它本来第三次就能成功。</li>
 *   <li><b>不该重试的拼命重试</b>：prompt 超长（400）会连着烧三次钱，且每次都慢。</li>
 *   <li><b>预算算错</b>：上界从「一个明确的数字」退化成「超时 × 尝试次数」的乘积。</li>
 *   <li><b>回调语义错</b>：把「还会再试」报成「不再重试」，前端就会显示错误的状态。</li>
 * </ul>
 *
 * <p>断言的锚点尽量选在<b>可观测行为</b>上（调用了几次、睡了多久、回调收到什么），
 * 而不是内部字段 —— 后者会让重构变成一场无意义的测试修补。
 */
class LlmRetryExecutorTest {

    /** 可手动推进的假时钟：让「花了多久」变成测试可以精确控制的输入。 */
    private static final class FakeClock {
        private long millis;

        void advance(long ms) {
            millis += ms;
        }

        long now() {
            return millis;
        }
    }

    @Test
    @DisplayName("首次就成功：只调用一次，不触发任何失败回调")
    void firstAttemptSuccessSkipsRetryMachinery() {
        LlmRetryExecutor executor = new LlmRetryExecutor(2, 2_000, 0, millis -> {
        }, () -> 0L);
        AtomicInteger calls = new AtomicInteger();
        List<String> events = new ArrayList<>();

        String value = executor.execute("单元测试调用", () -> {
            calls.incrementAndGet();
            return "done";
        }, (attempt, maxAttempts, cause, willRetry) -> events.add("失败回调被调用了"));

        assertEquals("done", value);
        assertEquals(1, calls.get());
        assertTrue(events.isEmpty(), "成功路径不该产生重试回调");
    }

    @Test
    @DisplayName("可重试异常：重试到成功，回调如实报告「还会再试」，退避按指数增长")
    void retryableFailureIsRetriedUntilSuccess() {
        FakeClock clock = new FakeClock();
        List<Long> sleeps = new ArrayList<>();
        LlmRetryExecutor executor = new LlmRetryExecutor(2, 2_000, 0, millis -> sleeps.add(millis), clock::now);
        AtomicInteger calls = new AtomicInteger();
        List<String> events = new ArrayList<>();

        String value = executor.execute("改写 Demo.java", () -> {
            clock.advance(1_000);
            if (calls.incrementAndGet() < 3) {
                throw new InternalServerException("error code: 524");
            }
            return "ok";
        }, (attempt, maxAttempts, cause, willRetry) -> events.add(attempt + "/" + maxAttempts + "/" + willRetry));

        assertEquals("ok", value);
        assertEquals(3, calls.get());
        assertEquals(List.of("1/3/true", "2/3/true"), events,
                "第三次成功了，所以只有前两次失败回调，且都标记为「还会再试」");
        assertEquals(List.of(2_000L, 4_000L), sleeps, "退避应为 base × 2^(n-1)");
    }

    @Test
    @DisplayName("不可重试异常（如 400 参数错）：只调用一次就抛出，不浪费时间与钱")
    void nonRetriableFailureFailsFast() {
        LlmRetryExecutor executor = new LlmRetryExecutor(2, 2_000, 0, millis -> {
        }, () -> 0L);
        AtomicInteger calls = new AtomicInteger();
        List<Boolean> willRetry = new ArrayList<>();

        assertThrows(InvalidRequestException.class, () -> executor.execute("改写 Demo.java", () -> {
            calls.incrementAndGet();
            throw new InvalidRequestException("prompt 超出上下文长度");
        }, (attempt, maxAttempts, cause, retry) -> willRetry.add(retry)));

        assertEquals(1, calls.get(), "400 重试一百次也是同样的结果，只是在烧钱");
        assertEquals(List.of(false), willRetry);
    }

    @Test
    @DisplayName("重试次数用尽：抛最后一次的异常，回调最后一次标记「不再重试」")
    void exhaustedRetriesRethrowLastFailure() {
        LlmRetryExecutor executor = new LlmRetryExecutor(2, 0, 0, millis -> {
        }, () -> 0L);
        AtomicInteger calls = new AtomicInteger();
        List<Boolean> willRetry = new ArrayList<>();

        InternalServerException thrown = assertThrows(InternalServerException.class,
                () -> executor.execute("改写 Demo.java", () -> {
                    calls.incrementAndGet();
                    throw new InternalServerException("error code: 524");
                }, (attempt, maxAttempts, cause, retry) -> willRetry.add(retry)));

        assertEquals(3, calls.get(), "1 次原始调用 + 2 次重试");
        assertEquals(List.of(true, true, false), willRetry, "最后一次必须标成「不再重试」");
        assertTrue(thrown.getMessage().contains("524"), "抛出的应当是最后一次的真实原因");
    }

    @Test
    @DisplayName("总预算不够再发起一次时提前止损 —— 而不是试到把预算撑爆")
    void budgetStopsBeforeOverspending() {
        FakeClock clock = new FakeClock();
        // 预算 2s，而一次尝试要 1.2s：第一次失败后已花 1.2s，
        // 再试一次预计总量 2.4s 会超过预算 —— 那就别发起这次注定超支的请求
        LlmRetryExecutor executor = new LlmRetryExecutor(2, 0, 2, millis -> {
        }, clock::now);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(InternalServerException.class, () -> executor.execute("改写 Demo.java", () -> {
            calls.incrementAndGet();
            clock.advance(1_200);
            throw new InternalServerException("error code: 524");
        }, LlmAttemptListener.NONE));

        assertEquals(1, calls.get(), "明知会超预算就不该再发起一次 1.2s 的请求");
    }

    @Test
    @DisplayName("预算充足时不会误杀：三次尝试刚好放得下就应当跑满")
    void budgetAllowsRetryWhenItStillFits() {
        FakeClock clock = new FakeClock();
        LlmRetryExecutor executor = new LlmRetryExecutor(2, 0, 5, millis -> {
        }, clock::now);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(InternalServerException.class, () -> executor.execute("改写 Demo.java", () -> {
            calls.incrementAndGet();
            clock.advance(1_000);
            throw new InternalServerException("error code: 524");
        }, LlmAttemptListener.NONE));

        assertEquals(3, calls.get(), "1s × 3 远小于 5s 预算，不应当被截断");
    }

    @Test
    @DisplayName("退避封顶 30s：重试次数多时也不会退避到天际")
    void backoffIsCapped() {
        List<Long> sleeps = new ArrayList<>();
        LlmRetryExecutor executor = new LlmRetryExecutor(6, 5_000, 0, millis -> sleeps.add(millis), () -> 0L);

        assertThrows(InternalServerException.class, () -> executor.execute("改写 Demo.java", () -> {
            throw new InternalServerException("error code: 503");
        }, LlmAttemptListener.NONE));

        assertEquals(6, sleeps.size());
        assertEquals(5_000L, sleeps.get(0));
        assertEquals(30_000L, sleeps.get(sleeps.size() - 1), "5s 起的指数增长必须被 30s 封顶");
    }

    @Test
    @DisplayName("异常分类：优先认 LangChain4j 的语义基类，其次状态码与网络类型，都认不出则不重试")
    void classifiesRetryability() {
        assertTrue(LlmRetryExecutor.isRetryable(new InternalServerException("error code: 524")),
                "实测就是这个：上游网关超时回的 524");
        assertTrue(LlmRetryExecutor.isRetryable(new InternalServerException("error code: 502")));
        assertTrue(LlmRetryExecutor.isRetryable(new RateLimitException("429")));
        assertTrue(LlmRetryExecutor.isRetryable(new dev.langchain4j.exception.TimeoutException("timeout")));
        assertTrue(LlmRetryExecutor.isRetryable(new HttpTimeoutException("read timed out")),
                "SDK 没包过的裸网络超时也要认");
        assertTrue(LlmRetryExecutor.isRetryable(new ConnectException("connection refused")));

        assertFalse(LlmRetryExecutor.isRetryable(new InvalidRequestException("bad request")));
        assertFalse(LlmRetryExecutor.isRetryable(new AuthenticationException("invalid api key")));
        assertFalse(LlmRetryExecutor.isRetryable(new HttpException(400, "bad request")));
        assertFalse(LlmRetryExecutor.isRetryable(new UnknownHostException("no such host")),
                "域名解析不了，重试只是把同一个失败再等一遍");
        assertFalse(LlmRetryExecutor.isRetryable(new IllegalStateException("未知异常")),
                "没有依据时保守不重试：重试等于用三倍的时间去确认同一个未知数");
    }

    @Test
    @DisplayName("失败原因要能直接给人看：状态码翻成人话")
    void describesFailureInPlainWords() {
        assertEquals("HTTP 524（上游响应超时）",
                LlmRetryExecutor.describeShort(new HttpException(524, "error code: 524")));
        assertEquals("HTTP 401（鉴权失败）",
                LlmRetryExecutor.describeShort(new HttpException(401, "invalid api key")));
        assertEquals("请求超时",
                LlmRetryExecutor.describeShort(new HttpTimeoutException("read timed out")));
    }

    @Test
    @DisplayName("noRetry 执行器：一次失败即抛出，退化成「直调一次」")
    void noRetryExecutesExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();

        assertThrows(InternalServerException.class, () -> LlmRetryExecutor.noRetry().execute(
                "单元测试调用", () -> {
                    calls.incrementAndGet();
                    throw new InternalServerException("error code: 524");
                }, LlmAttemptListener.NONE));

        assertEquals(1, calls.get());
        assertEquals(1, LlmRetryExecutor.noRetry().maxAttempts());
    }
}
