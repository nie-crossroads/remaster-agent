package com.remasteragent.core.queue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * {@link TaskQueue} 的内存实现，只服务于单测。
 *
 * <p>它把「新消息」和「待接管的遗留消息」分成两个独立的桶，而不是模拟 Redis 的
 * PEL 表 —— 因为消费逻辑真正需要区分的只有这两类：一类是刚投递的，
 * 一类是上次进程被杀留下的。模拟 PEL 的内部结构只会让测试更复杂、更脆，
 * 却不会多验证任何东西。
 *
 * <p>所有状态变化都有测试辅助方法可读，方便直接断言「确认了哪几条」，
 * 而不用去猜。
 */
public final class InMemoryTaskQueue implements TaskQueue {

    private final Deque<QueueMessage> fresh = new ArrayDeque<>();
    private final Deque<QueueMessage> reclaimable = new ArrayDeque<>();
    private final List<String> ackedHandles = new ArrayList<>();
    private final List<Long> enqueuedTaskIds = new ArrayList<>();

    private long sequence = 0;
    private int initializeCount = 0;

    @Override
    public void initialize() {
        initializeCount++;
    }

    @Override
    public void enqueue(long taskId) {
        enqueuedTaskIds.add(taskId);
        fresh.addLast(new QueueMessage("m-" + (++sequence), taskId));
    }

    @Override
    public List<QueueMessage> poll() {
        return drain(fresh);
    }

    @Override
    public void ack(String handle) {
        ackedHandles.add(handle);
    }

    @Override
    public List<QueueMessage> reclaimAbandoned() {
        return drain(reclaimable);
    }

    private static List<QueueMessage> drain(Deque<QueueMessage> source) {
        List<QueueMessage> drained = new ArrayList<>(source);
        source.clear();
        return drained;
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    /** 模拟「上一个 Worker 被杀」留下的未确认消息。 */
    public QueueMessage addReclaimable(long taskId) {
        QueueMessage message = new QueueMessage("p-" + (++sequence), taskId);
        reclaimable.addLast(message);
        return message;
    }

    /** 模拟一条格式不可读的脏消息。 */
    public QueueMessage addRaw(String handle, long taskId) {
        QueueMessage message = new QueueMessage(handle, taskId);
        fresh.addLast(message);
        return message;
    }

    public List<String> ackedHandles() {
        return List.copyOf(ackedHandles);
    }

    public List<Long> enqueuedTaskIds() {
        return List.copyOf(enqueuedTaskIds);
    }

    public boolean isInitialized() {
        return initializeCount > 0;
    }

    public int initializeCount() {
        return initializeCount;
    }

    public boolean isEmpty() {
        return fresh.isEmpty() && reclaimable.isEmpty();
    }
}
