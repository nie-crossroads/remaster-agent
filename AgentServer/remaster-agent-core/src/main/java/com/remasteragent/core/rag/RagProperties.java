package com.remasteragent.core.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 代码 RAG 的检索参数。
 *
 * <p>这几个数字都不是「调大了更好」——它们之间是有取舍的，写在这里方便面试时逐个解释：
 *
 * @param enabled       是否启用代码检索。<b>关掉它是最省钱的降级开关</b>：不检索就不需要索引、
 *                      不需要 embedding，REWRITE 退回「只看目标文件」。排查「检索是不是拖慢了任务」
 *                      时第一步就该关它。
 * @param topK          最终喂给模型的块数。取 6 是上下文预算与召回的折中：太小会漏掉关键依赖，
 *                      太大则会把无关代码塞进 prompt —— 既推高 token 成本，又稀释真正相关的信号。
 * @param candidateK    每路检索各自取回的候选数。三路各自多取一些，交给 RRF 融合去挑 ——
 *                      <b>单路取多、融合后取精</b>，比每路都只取 topK 效果更好（某一路的
 *                      第 10 名可能是融合后的第 1 名）。
 * @param rrfK          RRF 公式 {@code 1/(k + rank)} 里的 k。经典取 60：它让排名差异的
 *                      影响变得平滑，避免某一路的第一名因为「恰好排第一」就压制其它路的共识。
 * @param neighborDepth 依赖图邻居扩展的深度。0 = 不扩展。取 1 意味着「命中的类，把它直接
 *                      依赖/被依赖的类也带进来」——这是代码检索区别于普通文本 RAG 的关键：
 *                      单看一个方法往往看不出它为什么要这么写，看它的调用者/被调用者才清楚。
 */
@ConfigurationProperties(prefix = "remaster.rag")
public record RagProperties(
        Boolean enabled,
        Integer topK,
        Integer candidateK,
        Integer rrfK,
        Integer neighborDepth
) {

    public static final int DEFAULT_TOP_K = 6;
    public static final int DEFAULT_CANDIDATE_K = 20;
    public static final int DEFAULT_RRF_K = 60;
    public static final int DEFAULT_NEIGHBOR_DEPTH = 1;

    public RagProperties {
        enabled = enabled == null || enabled;
        topK = (topK == null || topK <= 0) ? DEFAULT_TOP_K : topK;
        candidateK = (candidateK == null || candidateK <= 0) ? DEFAULT_CANDIDATE_K : candidateK;
        rrfK = (rrfK == null || rrfK <= 0) ? DEFAULT_RRF_K : rrfK;
        neighborDepth = (neighborDepth == null || neighborDepth < 0) ? DEFAULT_NEIGHBOR_DEPTH : neighborDepth;
    }
}
