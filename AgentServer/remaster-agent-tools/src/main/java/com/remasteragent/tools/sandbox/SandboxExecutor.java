package com.remasteragent.tools.sandbox;

/**
 * 代码执行沙箱 —— 本项目唯一允许执行「模型生成的代码」的地方。
 *
 * <p><b>为什么要抽象这一层。</b>LLM 生成的代码是不可信的，直接在本机跑等于把
 * 主机交给一个随机程序。隔离手段会随环境演进（本机受限子进程 → Docker 容器 →
 * 更严格的 gVisor / microVM），所以这里只定义一个最小契约：
 * <b>执行一条命令，拿回结果</b>，不暴露任何进程或容器细节。
 * 换实现时上层（VERIFY 节点）一行都不用改。
 *
 * <p>已知的实现：
 * <ul>
 *   <li>{@link LocalProcessSandboxExecutor} —— 本机受限子进程。限超时、限内存、独立工作目录，
 *       但<b>不提供文件系统与网络隔离</b>，只能用于自有可信工程。</li>
 *   <li>Docker 容器实现 —— 计划中，需本机装 Docker。</li>
 * </ul>
 *
 * <p>实现必须遵守的约定：
 * <ol>
 *   <li>不得修改 {@link SandboxRequest#workspace()} 之外的任何文件</li>
 *   <li>超时后必须强制结束进程，不能让它继续跑</li>
 *   <li>必须捕获全部输出，不能因为管道缓冲区满而卡死</li>
 * </ol>
 */
public interface SandboxExecutor {

    /**
     * 执行一条命令并等待结束。
     *
     * @param request 执行请求
     * @return 执行结果，永不返回 null；失败通过 {@link SandboxResult} 表达而不是抛异常
     */
    SandboxResult execute(SandboxRequest request);

    /** 实现名称，用于日志与指标标注（如 local / docker）。 */
    String mode();
}
