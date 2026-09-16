package com.remasteragent.common.domain;

/**
 * 人工门禁（GATE 节点）的审批状态。
 *
 * <p>与节点状态、任务状态是三套不同的枚举，刻意不合并：
 * <ul>
 *   <li>{@link TaskStatus#WAITING_HUMAN} 描述「整个任务停下来了」；</li>
 *   <li>{@link NodeStatus} 描述「一个节点跑到哪一步了」；</li>
 *   <li>本枚举描述「这道门禁本身批没批」。一次挂起会产生 <b>一行</b> PENDING 记录，
 *       人做出决定后它变成 APPROVED / REJECTED —— 历史决定不会被覆盖，
 *       所以「这道门当时是谁、什么时候、以什么理由放行的」永远查得到。</li>
 * </ul>
 */
public enum GateStatus {
    /** 已挂起，等待人工审批 */
    PENDING,
    /** 人工放行，任务从该 checkpoint 继续 */
    APPROVED,
    /** 人工驳回，任务就此判失败 */
    REJECTED
}
