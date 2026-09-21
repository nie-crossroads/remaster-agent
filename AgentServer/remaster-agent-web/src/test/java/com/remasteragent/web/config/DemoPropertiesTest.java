package com.remasteragent.web.config;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 演示网关两个共享账号的默认值。
 *
 * <p>为什么值得单独锁住：这两个默认值只在<b>没有配环境变量时</b>生效 ——
 * 也就是「本机直接起服务」和「新机器第一次部署」这两条路径。它们一旦漂移，
 * 现象是「照文档敲的账号登不进去」，而文档和代码看起来都对。
 */
@DisplayName("DemoProperties：演示账号默认值")
class DemoPropertiesTest {

    @Test
    @DisplayName("未配置 root 时用默认账号 root / remaster-root")
    void rootFallsBackToDefaults() {
        DemoProperties props = new DemoProperties(true, List.of(), 2, null, null, null, null);

        assertEquals("demo", props.username());
        assertEquals("remaster-demo", props.password());
        assertEquals("root", props.rootUsername());
        assertEquals("remaster-root", props.rootPassword());
    }

    @Test
    @DisplayName("显式配置（含空白串）时的取值")
    void explicitValuesAreKeptAndBlanksFallBack() {
        DemoProperties explicit = new DemoProperties(true, List.of(), 2,
                "alice", "pw1", "bob", "pw2");
        assertEquals("alice", explicit.username());
        assertEquals("bob", explicit.rootUsername());

        // 空白串按「没配」处理，避免出现空用户名这种既登不进也说不清的状态
        DemoProperties blank = new DemoProperties(true, List.of(), 2, "  ", "", "  ", "");
        assertEquals("demo", blank.username());
        assertEquals("remaster-demo", blank.password());
        assertEquals("root", blank.rootUsername());
        assertEquals("remaster-root", blank.rootPassword());
    }
}
