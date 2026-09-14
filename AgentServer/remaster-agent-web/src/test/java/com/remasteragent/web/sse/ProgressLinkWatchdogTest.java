package com.remasteragent.web.sse;

import com.remasteragent.core.progress.ProgressEvent;
import com.remasteragent.core.progress.ProgressLink;
import com.remasteragent.core.progress.ProgressPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进度链路看门狗的单测。
 *
 * <h2>为什么这块逻辑必须被单测钉死</h2>
 * <p>它防的故障（公网 Redis 的订阅连接被静默回收）只在真实网络里偶发，靠端到端去试
 * 可能要等几十分钟才复现一次、还未必能复现。而判据本身是纯逻辑 ——
 * 「静默多久算死」「什么时候该重建」完全可以用假实现确定性验证。
 *
 * <p>这里验证的是「看门狗会不会在该动手的时候动手、不该动手的时候不动手」，
 * 而不是「Redis 会不会断」—— 后者不是代码能保证的，前者才是。
 */
class ProgressLinkWatchdogTest {

    private static final long STALE_MILLIS = 45_000L;

    private final List<ProgressEvent> published = new ArrayList<>();
    private int rebuilds;
    private long lastSignalAt;
    private boolean confirmedAlive;

    private final ProgressLink link = new ProgressLink() {
        @Override
        public long lastSignalAt() {
            return lastSignalAt;
        }

        @Override
        public boolean isConfirmedAlive() {
            return confirmedAlive;
        }

        @Override
        public void recreateSubscription() {
            rebuilds++;
        }
    };

    private ProgressLinkWatchdog watchdog() {
        return new ProgressLinkWatchdog(link, published::add, STALE_MILLIS);
    }

    @Test
    @DisplayName("尚未建立订阅时什么都不做：不打探、也不凭空造一条连接")
    void doesNothingBeforeAnySubscription() {
        lastSignalAt = 0L;

        watchdog().pingAndCheck();

        assertEquals(0, rebuilds);
        assertTrue(published.isEmpty(), "没有订阅者时发心跳只会白占一条 Redis 连接");
    }

    @Test
    @DisplayName("链路新鲜时只探活、不重建")
    void freshLinkIsPingedButNotRebuilt() {
        lastSignalAt = System.currentTimeMillis();

        watchdog().pingAndCheck();

        assertEquals(0, rebuilds, "链路正常却重建订阅，等于自己制造一次断流");
        assertEquals(1, published.size());
        assertTrue(published.get(0).isHeartbeat(), "探活必须用心跳事件，不能被当成任务进度");
    }

    @Test
    @DisplayName("链路静默超过阈值即重建订阅 —— 只打日志不重建等于没修")
    void staleLinkTriggersRebuild() {
        lastSignalAt = System.currentTimeMillis() - STALE_MILLIS - 1_000L;

        watchdog().pingAndCheck();

        assertEquals(1, rebuilds, "订阅连接死了却没有别的办法恢复，不重建就永远收不到增量");
        assertEquals(1, published.size(), "重建完要立刻发一条心跳，让新链路尽早得到验证");
    }

    @Test
    @DisplayName("恢复之前每轮都继续重试重建，不会静默放弃")
    void keepsRebuildingWhileStillStale() {
        lastSignalAt = System.currentTimeMillis() - STALE_MILLIS - 1_000L;
        ProgressLinkWatchdog watchdog = watchdog();

        watchdog.pingAndCheck();
        watchdog.pingAndCheck();

        assertEquals(2, rebuilds, "一次重建没成功就放弃，只会让页面永远停在旧状态");
        assertEquals(2, published.size());
    }

    @Test
    @DisplayName("重建后链路时间戳被刷新，就不会再重复重建")
    void rebuildResetsTheClockAndStopsRebuilding() {
        lastSignalAt = System.currentTimeMillis() - STALE_MILLIS - 1_000L;
        ProgressLinkWatchdog watchdog = watchdog();

        watchdog.pingAndCheck();
        assertEquals(1, rebuilds);

        // 模拟重建订阅：订阅端把「信号时刻」重置为刚刚
        lastSignalAt = System.currentTimeMillis();
        watchdog.pingAndCheck();

        assertEquals(1, rebuilds, "刚重建过又立刻重建，会把链路拖进「一直断」的死循环");
    }

    @Test
    @DisplayName("恢复判定：重建后必须真的收到过消息才算恢复")
    void recoveryRequiresAConfirmedMessage() {
        lastSignalAt = System.currentTimeMillis() - STALE_MILLIS - 1_000L;
        ProgressLinkWatchdog watchdog = watchdog();
        watchdog.pingAndCheck();

        // 重建后时钟刷新，但还没收到任何消息 —— 此时不算恢复
        lastSignalAt = System.currentTimeMillis();
        confirmedAlive = false;
        watchdog.pingAndCheck();
        assertEquals(1, rebuilds, "「没消息」不等于「链路坏」，但也不等于「已恢复」");

        // 收到消息之后才真正恢复：链路时间戳继续推进，且不再重建
        confirmedAlive = true;
        lastSignalAt = System.currentTimeMillis();
        watchdog.pingAndCheck();
        assertEquals(1, rebuilds);
    }

    @Test
    @DisplayName("陈旧判定的边界：从未订阅不算、正好等于阈值不算、超过 1ms 就算")
    void staleBoundary() {
        assertFalse(ProgressLinkWatchdog.isStale(0L, 1_000_000L, STALE_MILLIS),
                "0 是「从未订阅」的哨兵，不能被当成「已经死了很久」");
        assertFalse(ProgressLinkWatchdog.isStale(1_000_000L, 1_000_000L + STALE_MILLIS, STALE_MILLIS),
                "正好卡在阈值上仍算健康，避免抖动即触发");
        assertTrue(ProgressLinkWatchdog.isStale(1_000_000L, 1_000_000L + STALE_MILLIS + 1L, STALE_MILLIS));
    }
}
