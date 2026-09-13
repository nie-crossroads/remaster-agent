package com.remasteragent.core.queue;

import java.util.List;

/**
 * 任务队列 —— API 进程投递、Worker 进程消费。
 *
 * <p>抽成接口的理由和 {@code TaskStore} 一样：让消费循环的语义（拿到什么、何时确认、
 * 何时接管别人没干完的活）可以脱离 Redis 单测。Redis 实现是 {@link RedisTaskQueue}。
 *
 * <h2>为什么用 Stream 而不是 List / Pub/Sub</h2>
 * <ul>
 *   <li><b>List（{@code LPUSH}/{@code BRPOP}）</b>：消息一旦被弹出就从队列消失。
 *       Worker 取到任务后进程被杀，这个任务就永久丢了 —— 而本项目跑的是一次几十分钟的
 *       迁移，被杀的窗口相当大。</li>
 *   <li><b>Pub/Sub</b>：没有任何持久化，订阅者不在线消息就没了。适合进度事件，
 *       绝不适合任务投递。</li>
 *   <li><b>Stream + 消费组</b>：收到消息先进 PEL（未确认表），处理完才 ACK。
 *       进程被杀 → 消息仍在 PEL 里 → 空闲超时后被其它 Worker 接管重跑。
 *       这就是「至少一次投递」，配合节点级幂等（已成功的节点不重跑）正好消掉重复。</li>
 * </ul>
 *
 * <h2>投递语义的诚实说明</h2>
 * <p>这是<b>至少一次</b>，不是恰好一次。同一个任务可能被执行两遍。
 * 之所以不构成问题，是因为 {@code DagScheduler} 会先查 checkpoint，已成功的节点直接跳过 ——
 * 重复投递的代价是「重放一次工作目录」，而不是「再烧一遍 token」。
 * 换成「恰好一次」需要分布式事务，成本远高于收益。
 */
public interface TaskQueue {

    /**
     * 一条队列消息。
     *
     * @param handle Redis 侧的消息 id，ACK 时用它定位。刻意不让上层直接接触
     *               {@code RecordId} —— 那是 Redis 的概念，不该渗到消费逻辑里
     * @param taskId 迁移任务 id
     */
    record QueueMessage(String handle, long taskId) {
    }

    /**
     * 幂等地准备好队列（创建 Stream 与消费组）。
     *
     * <p>消费组起始偏移取 {@code 0}（从最早的消息开始）而不是 {@code $}（只看新消息）：
     * 这样「先投递任务、后启动 Worker」也不会丢任务。生产环境的顺序恰好经常如此 ——
     * API 进程先起来接受请求，Worker 稍后才启动。
     */
    void initialize();

    /** 投递一个任务。 */
    void enqueue(long taskId);

    /**
     * 取一批新消息。阻塞等待至多 {@code blockMillis}，没有消息时返回空列表。
     *
     * <p>返回的消息<b>必须</b>由调用方在处理完成后 {@link #ack} 确认，否则会被重复投递。
     */
    List<QueueMessage> poll();

    /** 确认消息已处理完。 */
    void ack(String handle);

    /**
     * 接管「上一个 Worker 死了、留在 PEL 里没人确认」的消息。
     *
     * <p>没有这一步的话，「至少一次投递」这个承诺就是假的：消息确实没丢，
     * 但它永远挂在一个已经不存在的消费者的名下，没有谁会再读到它。
     *
     * <p><b>返回值不能忽略。</b> 被接管的消息不会出现在 {@link #poll()} 的结果里 ——
     * {@code XREADGROUP} 用 {@code >} 只读「新」消息，而接管的记录早就有 id 了。
     * 所以必须把接管到的消息当作刚取到的一样处理，处理完再 {@link #ack}。
     *
     * @return 被接管的、需要立刻处理的消息
     */
    List<QueueMessage> reclaimAbandoned();
}
