package com.remasteragent.web.api;

/**
 * 请求与当前状态冲突 —— 映射为 HTTP 409。
 *
 * <p>与 {@link NotFoundException}（404）和参数错误（400）分开，是因为这三者对调用方
 * <b>意味着完全不同的动作</b>：404 是「你找错了对象」，400 是「你的请求本身没写对」，
 * 而 409 是「请求没错、对象也在，但它现在不是这个状态」。
 *
 * <p>具体到规划评审：「批准」只能作用在等待评审的任务上。如果任务压根没开启评审、
 * 或者已经批准过一次，再批一次就是典型的 409 —— 而 <b>幂等性在这里不是形式主义</b>：
 * 批准的动作里带着一次「重新入队」，重复批准会让同一个任务被投递两次，
 * 两个 Worker 同时跑同一个 DAG，落盘互相覆盖。所以这个状态判断是数据一致性的防线，
 * 不是接口洁癖。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
