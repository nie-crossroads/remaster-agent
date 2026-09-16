package com.remasteragent.core.trace;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 把导出的 span 收在内存里，供断言读取。
 *
 * <p>刻意<b>不引 {@code opentelemetry-sdk-testing}</code>（它自带一个
 * {@code InMemorySpanExporter}）：本项目对 OTel 只用 api + sdk 两个 artifact，
 * 为了一句断言多引一条测试依赖，就把「这个项目依赖了多少东西」这件事弄模糊了。
 * 这个实现只有二十行，而且它的行为一眼可读 —— 这比一个外部依赖更值得信任。
 *
 * <p>{@code spans} 用同步列表：{@code SimpleSpanProcessor} 在 {@code end()} 的调用线程上
 * 直接导出，没有跨线程交付，所以不需要并发容器。这一点很重要 —— 若换成
 * {@code BatchSpanProcessor}，断言就必须等一个不确定的导出延迟，测试会开始随机变红。
 */
final class CollectingSpanExporter implements SpanExporter {

    private final List<SpanData> spans = new ArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> batch) {
        spans.addAll(batch);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    List<SpanData> spans() {
        return List.copyOf(spans);
    }

    /** 按名字取唯一一条 span；取不到或有多条都直接失败 —— 名字重复本身就是缺陷。 */
    SpanData single(String name) {
        List<SpanData> matched = spans.stream().filter(s -> s.getName().equals(name)).toList();
        if (matched.size() != 1) {
            throw new AssertionError("期望恰好一条名为 " + name + " 的 span，实际 " + matched.size()
                    + " 条；现有: " + spans.stream().map(SpanData::getName).toList());
        }
        return matched.get(0);
    }
}
