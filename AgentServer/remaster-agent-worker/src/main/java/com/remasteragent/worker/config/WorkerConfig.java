package com.remasteragent.worker.config;

import com.remasteragent.core.engine.DagScheduler;
import com.remasteragent.core.queue.QueueProperties;
import com.remasteragent.core.queue.TaskConsumer;
import com.remasteragent.core.queue.TaskQueue;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.core.workspace.WorkspaceCleaner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Worker 装配：启动任务消费循环。
 *
 * <p>用 {@code initMethod = "start"} 而不是在构造函数里启动，是为了让
 * 「对象构造完成」与「开始拉取任务」之间留出一个可见的边界 ——
 * 构造期启动后台线程时，线程可能在本对象还没被 Spring 完全初始化好的时候就跑起来。
 *
 * <p>{@code destroyMethod = "close"} 保证停机时先停止消费循环、再关连接池：
 * 反过来的话，正在执行的任务会在写 checkpoint 时撞上「连接池已关闭」，
 * 把一个本来能正常收尾的任务变成一条脏数据。
 */
@Configuration
public class WorkerConfig {

    @Bean(initMethod = "start", destroyMethod = "close")
    public TaskConsumer taskConsumer(TaskQueue queue, TaskStore taskStore,
                                     DagScheduler scheduler, QueueProperties properties,
                                     WorkspaceCleaner workspaceCleaner) {
        return new TaskConsumer(queue, taskStore, scheduler, properties, workspaceCleaner);
    }
}
