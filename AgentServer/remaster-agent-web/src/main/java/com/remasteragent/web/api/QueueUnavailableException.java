package com.remasteragent.web.api;

/**
 * 任务投递到队列失败 —— 映射为 HTTP 503。
 *
 * <p><b>为什么需要这个专门的异常，而不是让它落进 500 兜底。</b>创建任务的流程是
 * 「先落库拿到自增 id → 再投队列」，两步之间没有事务能跨 Redis 与 PostgreSQL。
 * 于是存在一个真实的中间态：<b>任务行已经在库里，但没有任何 Worker 会来取它</b>。
 * 如果只是抛异常回去，这条任务会永远停在 {@code PENDING} —— 界面上看起来像「排队中」，
 * 实际上永远不会动，而调用方拿到的 500 也说不清「到底建没建」。
 *
 * <p>所以处理方式是把它变成一句明确的话：任务已标记为 FAILED（不留悬空状态），
 * 并且以 503 告诉调用方「是我的下游依赖暂时不可用，你可以重试」——
 * 这与 400（你的请求写错了）和 500（我崩了）都不同，调用方的处理动作也不同：
 * 前者要改请求，这里只需要稍后重发。
 */
public class QueueUnavailableException extends RuntimeException {

    public QueueUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
