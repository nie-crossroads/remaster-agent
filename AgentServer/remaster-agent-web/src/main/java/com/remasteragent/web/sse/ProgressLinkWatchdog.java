package com.remasteragent.web.sse;

import com.remasteragent.core.progress.ProgressEvent;
import com.remasteragent.core.progress.ProgressLink;
import com.remasteragent.core.progress.ProgressPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 进度订阅链路的看门狗 —— 定期探活，发现「静默失效」就重建订阅。
 *
 * <h2>它防的是哪一类故障</h2>
 * <p>典型的场景：Redis 在公网（本项目 {@code .env} 指向一个远程实例），中间的 NAT/防火墙
 * 会把长时间没有流量的连接悄悄回收。而 Pub/Sub 的<b>订阅连接是只读的</b> ——
 * 客户端从不在上面写，所以 TCP 层不会触发重传超时，Lettuce 也不会收到任何错误，
 * {@code netstat} 照样显示 ESTABLISHED。结果是：
 *
 * <ul>
 *   <li>快照照常到达（它走数据库，与这条连接无关）；</li>
 *   <li>SSE 心跳照常到达（它是往浏览器连接写，方向相反）；</li>
 *   <li><b>唯独增量事件一条都到不了</b> —— 页面永远停在打开页面的那一刻，
 *       而日志里一句错都没有。</li>
 * </ul>
 *
 * <p>这是实测踩到的：直连后端 45 秒只收到快照、零增量，重启 API 立刻恢复；
 * 同时一个裸 TCP 探针订阅同一个频道能收到 Worker 发的全部事件 ——
 * 证明发布端没问题，是订阅端已经聋了却没人知道。
 *
 * <h2>为什么要自己发心跳，而不是等 Worker 的事件</h2>
 * <p>因为「没消息」和「链路死了」在只有业务事件时是<b>不可区分</b>的：一次沙箱构建可能几十秒
 * 没有任何节点状态变化，那段时间里链路好得很，看门狗却会一直误判。
 * 让一条自己的心跳定期走完「发布 → Redis → 订阅连接」的整条回路，
 * 才能把「真的没消息」与「消息根本进不来」分开。心跳本身还顺带把订阅连接喂活，
 * 从源头减少连接被回收的概率。
 *
 * <p>心跳事件在订阅端被直接拦下、不派发（见 {@code RedisProgressEventSubscriber}），
 * 所以 SSE 客户端不会看到任何多余事件。
 *
 * <h2>恢复必须是「被证明的」</h2>
 * <p>重建订阅不等于链路恢复。所以只有真的收到过一条消息（{@link ProgressLink#isConfirmedAlive()}）
 * 才会记「已恢复」；否则只是在重建后重新计时，避免打出一句自己都没验证过的日志。
 */
@Component
public class ProgressLinkWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ProgressLinkWatchdog.class);

    private final ProgressLink link;
    private final ProgressPublisher publisher;
    private final long staleMillis;

    /** 是否已判定为「链路降级」。用于把告警压成「进入降级时一条 WARN」，而不是每轮刷屏。 */
    private volatile boolean degraded;

    public ProgressLinkWatchdog(ProgressLink link,
                                ProgressPublisher publisher,
                                @Value("${remaster.web.progress-stale-millis:45000}") long staleMillis) {
        this.link = link;
        this.publisher = publisher;
        this.staleMillis = staleMillis;
    }

    /**
     * 探活一轮：判陈旧 → 必要时重建 → 发心跳。
     *
     * <p>心跳放在最后发：万一刚刚重建过订阅，这一条心跳就落在新连接上，
     * 同一轮内就能完成一次「新链路通不通」的验证，不必再等一个周期。
     */
    @Scheduled(fixedDelayString = "${remaster.web.progress-ping-millis:15000}")
    public void pingAndCheck() {
        long lastSignal = link.lastSignalAt();
        if (lastSignal <= 0L) {
            // 还没人建立订阅 —— 没有链路可言，硬发心跳只会凭空多一个连接出来
            return;
        }

        long now = System.currentTimeMillis();
        if (isStale(lastSignal, now, staleMillis)) {
            if (degraded) {
                log.debug("进度订阅链路仍处于降级状态，再次重建订阅");
            } else {
                log.warn("进度订阅链路已静默失效 {} 秒（远端 Redis 的订阅连接可能已被网络设备回收），正在重建订阅",
                        (now - lastSignal) / 1000);
            }
            degraded = true;
            link.recreateSubscription();
        } else if (degraded && link.isConfirmedAlive()) {
            log.info("进度订阅链路已恢复：重建后重新收到消息");
            degraded = false;
        }

        publisher.publish(ProgressEvent.heartbeat());
    }

    /**
     * 链路是否已陈旧到需要重建。
     *
     * <p>抽成静态纯函数是为了能被确定性单测钉死 —— 「静默多久算死」这件事，
     * 靠端到端去试要等一次真实的公网连接被回收，成本高得离谱。
     *
     * <p>{@code lastSignalAt <= 0} 表示从未订阅，不算陈旧：那不该触发重建（也没什么可重建的）。
     */
    static boolean isStale(long lastSignalAt, long now, long staleMillis) {
        return lastSignalAt > 0L && now - lastSignalAt > staleMillis;
    }
}
