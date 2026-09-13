package com.remasteragent.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.util.concurrent.CountDownLatch;

/**
 * Worker 进程入口 —— 真正执行迁移的地方。
 *
 * <h2>为什么它刻意不带 Web</h2>
 * <p>这个进程只做一件事：从 Redis Stream 取任务、驱动 DAG、把进度事件发回去。
 * 不引 {@code spring-boot-starter-web} 有两层意义：
 * <ul>
 *   <li><b>能力边界即攻击面</b>：它没有任何对外端口，就无法被 HTTP 打进来。
 *       这个进程会执行任意 Java 工程的构建，少一个暴露面是实打实的收益。</li>
 *   <li><b>它逼着架构保持诚实</b>：一旦有人图省事想在这里加个接口，
 *       会发现得先改依赖 —— 这个摩擦本身就是提醒：进程边界是有意画的。</li>
 * </ul>
 * 显式设成 {@link WebApplicationType#NONE} 是为了让意图写在代码里，
 * 而不是靠「恰好没引 web 依赖」这种隐式事实。
 *
 * <h2>为什么必须显式把主线程停住（这是踩出来的坑）</h2>
 * <p>Spring Boot 的非 web 应用有一个反直觉但真实的行为：<b>{@code main} 一返回，
 * JVM 就退出</b>。因为此时容器里通常没有任何非守护线程 —— 而 Tomcat 那种
 * 「有人占着非守护线程所以进程活着」的错觉只在 web 应用里成立。
 *
 * <p>本项目正好踩中：消费循环跑在<b>守护线程</b>上（见 {@code TaskConsumer}），
 * 于是「启动日志一切正常、消费循环已启动、Started WorkerApplication」之后，
 * 进程立刻结束并触发 Spring 的关闭钩子。整个过程在日志里看起来像是
 * 「Worker 起来了，然后优雅停机了」——完全不像崩溃，所以极难归因；
 * 而在 API 进程那一侧看到的现象只是「任务投进去了但永远没人消费」。
 *
 * <p>顺带说明为什么不在 {@code TaskConsumer} 里把线程改成非守护线程：
 * 那样会让单测 JVM 挂住（surefire 跑完不退出），而且把「进程该活多久」这个
 * 决策塞进了一个本该只关心「怎么消费」的类里。进程生命周期归进程入口管。
 *
 * <p>停机的路径：收到 SIGTERM/SIGINT → Spring 的关闭钩子关闭容器 →
 * 触发 {@code ContextClosedEvent} → 这里解除阻塞 → {@code main} 返回 →
 * 同时容器关闭会调用 {@code TaskConsumer.close()} 让消费循环退出。
 */
@SpringBootApplication(scanBasePackages = "com.remasteragent")
public class WorkerApplication {

    private static final Logger log = LoggerFactory.getLogger(WorkerApplication.class);

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(WorkerApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext context = application.run(args);

        blockUntilContextCloses(context);
    }

    /**
     * 阻塞主线程直到容器关闭，借此把进程的存活时间交还给 Spring 的生命周期。
     *
     * <p>用监听 {@link ContextClosedEvent} 而不是简单地 {@code new CountDownLatch(1).await()}：
     * 后者永远醒不来，一旦将来需要在关闭后做点收尾（比如清理沙箱目录、上报退出码）
     * 就没地方落脚了。
     */
    private static void blockUntilContextCloses(ConfigurableApplicationContext context) {
        CountDownLatch closed = new CountDownLatch(1);
        context.addApplicationListener(event -> {
            if (event instanceof ContextClosedEvent) {
                closed.countDown();
            }
        });

        log.info("Worker 已就绪，等待任务投递（Ctrl+C 停止）");
        try {
            closed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("主线程被中断，Worker 即将退出");
        }
    }
}
