package com.example.negative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 这个测试故意写成「现代化之后才成立的样子」：它依赖一个本工程里【不存在】的辅助类
 * {@code com.example.negative.LeakFreeIndex}。要编过，必须新建这个类 ——
 * 但 RemasterAgent 只改写 entryFile 单文件，新建类超出它的能力，因此基线即红，
 * 且 Agent 改写 ApiLeak 也救不回来。这正是负样本要验证的：
 * 有些「正确现代化」在单文件改写下不可达。
 */
class ApiLeakTest {

    @Test
    @DisplayName("放进去能取出来（但测试还要求一个不存在的现代化类）")
    void putAndGetRequiresMissingClass() {
        ApiLeak store = new ApiLeak();
        store.put("api.key", "s3cr3t");
        assertEquals("s3cr3t", store.get("api.key"));

        // 现代化后的契约：索引应抽成独立的不可变视图类
        LeakFreeIndex idx = new LeakFreeIndex(store);   // 编译失败：类不存在
        Map<String, String> view = idx.snapshot();
        assertEquals("s3cr3t", view.get("api.key"));
    }
}
