package com.remasteragent.web.config;

import com.remasteragent.core.engine.DagScheduler;
import com.remasteragent.core.queue.QueueProperties;
import com.remasteragent.core.queue.TaskConsumer;
import com.remasteragent.core.queue.TaskQueue;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.core.workspace.WorkspaceCleaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 开发期内嵌 Worker —— 由 {@code remaster.worker.embedded=true} 打开。
 *
 * <h2>为什么值得留这个开关</h2>
 * <p>架构上 API 与 Worker 是两个进程，这也是要能讲清楚的设计。但要跑通一次端到端验证，
 * 开两个终端、两个 JVM、两份配置的成本很高，会让人（包括自己）懒得验证 ——
 * 而一个「懒得跑」的项目等于没有验证。所以留这个开关：开发时一个进程搞定，
 * 演示与部署时关掉它，走货真价实的双进程。
 *
 * <p><b>它不改变任何架构语义</b>：内嵌时用的还是同一个 {@link TaskConsumer}、
 * 同一个 Redis Stream、同一套 checkpoint。区别只是进程边界画在哪里。
 *
 * <h2>为什么不把这段放进 core</h2>
 * <p>{@code @ConditionalOnProperty} 属于 {@code spring-boot-autoconfigure}。
 * 若把带条件注解的装配写进 core，core 就得为了一个开发便利去依赖 autoconfigure，
 * 而那正是「core 保持可纯单测」这条约束要防的东西。放在应用层，由应用自己决定。
 */
@Configuration
public class EmbeddedWorkerConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedWorkerConfig.class);

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "remaster.worker", name = "embedded", havingValue = "true")
    public TaskConsumer embeddedTaskConsumer(TaskQueue queue, TaskStore taskStore,
                                             DagScheduler scheduler, QueueProperties properties,
                                             WorkspaceCleaner workspaceCleaner) {
        log.warn("内嵌 Worker 已启用（remaster.worker.embedded=true）：任务将在 API 进程内执行。"
                + "演示与部署请把它设为 false，改用独立的 Worker 进程。");
        return new TaskConsumer(queue, taskStore, scheduler, properties, workspaceCleaner);
    }
}
