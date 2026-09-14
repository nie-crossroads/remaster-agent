package com.remasteragent.core.rag;

import com.remasteragent.common.rag.RetrievedChunk;

import java.util.List;

/**
 * 上下文检索器 —— 改写节点依赖的抽象，而不是具体的 {@link HybridRetriever}。
 *
 * <p>抽这一层接口只有一个目的，但在本项目里很关键：<b>让 REWRITE 节点能被单测</b>。
 * 改写是整条流水线里最需要被确定性验证的节点（护栏、失败反馈、落盘都在它身上），
 * 如果它硬依赖「连数据库 + 算向量」的检索器，那么每个改写单测都会变成集成测试。
 * 换成这个接口后，单测塞一个 {@link #NONE} 就能跑，生产的 {@code HybridRetriever} 一行不改。
 *
 * <p>这与 {@code TaskStore} / {@code SandboxExecutor} 是同一套思路：把「外部依赖」收敛成接口，
 * 核心逻辑就永远是纯的、可测的。
 */
@FunctionalInterface
public interface ContextRetriever {

    /**
     * 检索与查询相关的代码块。
     *
     * @param projectRoot 工程根目录
     * @param query       查询意图（通常是被改写文件的路径）
     * @return 相关代码块；应<b>永不抛异常</b> —— 检索失败时返回空列表，让改写退回单文件模式
     */
    List<RetrievedChunk> retrieve(String projectRoot, String query);

    /** 空检索器 —— 未启用检索、或单元测试里不需要检索时使用。 */
    ContextRetriever NONE = (projectRoot, query) -> List.of();
}
