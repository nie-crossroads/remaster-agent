package com.remasteragent.core.engine.node;

import com.remasteragent.common.agent.AnalyzeResult;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeExecutor;
import com.remasteragent.core.engine.NodeOutcome;
import com.remasteragent.core.rag.CodeIndexer;
import com.remasteragent.core.rag.IndexStats;
import com.remasteragent.core.rag.RagProperties;
import com.remasteragent.tools.ast.JavaSourceAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * ANALYZE 节点：读懂目标文件，产出后续改写需要的上下文。
 *
 * <p>它有一个容易被忽略但很关键的作用：<b>把原始源码快照进 checkpoint</b>。
 * 后续每一次重写都基于这份快照整文件覆盖，而不是在上一次失败的产物上继续改。
 * 这样一来「回滚」这个动作就不需要任何补偿逻辑 —— 失败尝试的产物在下一次覆盖时自然消失。
 * 如果改成增量修改，你就必须额外实现一套真正的回滚机制，而那正是这类系统最容易出错的地方。
 */
@Component
public class AnalyzeNode implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeNode.class);

    /** 节点的业务键。定义成常量，因为它同时被调度器和下游节点引用。 */
    public static final String NODE_KEY = "analyze";

    private final CodeIndexer codeIndexer;
    private final RagProperties ragProperties;

    public AnalyzeNode(CodeIndexer codeIndexer, RagProperties ragProperties) {
        this.codeIndexer = codeIndexer;
        this.ragProperties = ragProperties;
    }

    @Override
    public NodeType type() {
        return NodeType.ANALYZE;
    }

    @Override
    public NodeOutcome execute(NodeContext context) {
        String entryFile = context.task().entryFile();
        Path target = context.workspace().resolve(entryFile).normalize();

        if (!target.startsWith(context.workspace().normalize())) {
            return NodeOutcome.fail("入口文件路径越出了工作目录，拒绝执行: " + entryFile);
        }
        if (!Files.isRegularFile(target)) {
            return NodeOutcome.fail("入口文件不存在: " + target);
        }

        try {
            String source = Files.readString(target, StandardCharsets.UTF_8);
            AnalyzeResult result = JavaSourceAnalyzer.analyze(entryFile, source);
            log.info("分析完成: {} → 包={} 类型={} 符号={} 个",
                    entryFile, result.packageName(), result.className(), result.symbols().size());
            indexProject(context);
            return NodeOutcome.ok(result);
        } catch (IOException e) {
            return NodeOutcome.fail("读取源文件失败: " + e.getMessage());
        } catch (Exception e) {
            // 源码解析不了 —— 说明被测文件本身就是坏的，这属于任务级问题，重试没有意义
            return NodeOutcome.fail("源码无法解析，任务前提不成立: " + e.getMessage());
        }
    }

    /**
     * 索引整个工程 —— 阶段 2（理解层）的入口动作。
     *
     * <p>索引的是<b>原始工程</b>（{@code task.projectRoot()}）而不是沙箱工作目录：
     * 检索的目的是「参考工程里别处的写法」，原始工程才是权威来源；工作目录是本次任务的临时副本，
     * 里面还混着上一轮改写产物，拿它做索引会污染后续轮次的上下文。
     *
     * <p><b>失败只降级、不中断任务。</b>索引/向量化属于「增强」，不是「前提」：
     * 拿不到索引时改写退回阶段 1 的单文件模式，任务仍应有机会成功。
     * 真正的失败在检索侧会被显式记录（找不到 repo 就跳过检索），不会被这里吞掉。
     */
    private void indexProject(NodeContext context) {
        if (!ragProperties.enabled()) {
            log.info("代码检索已关闭（remaster.rag.enabled=false），跳过工程索引");
            return;
        }
        String projectRoot = context.task().projectRoot();
        try {
            Path path = Paths.get(projectRoot);
            String name = path.getFileName() == null ? projectRoot : path.getFileName().toString();
            IndexStats stats = codeIndexer.index(projectRoot, name);
            log.info("工程索引完成: 文件={} 块={} 向量={}",
                    stats.files(), stats.chunks(), stats.vectorized() ? "已写入" : "未启用");
        } catch (Exception e) {
            log.warn("工程索引失败，检索将不可用（任务继续，改写退回单文件模式）: {}", e.getMessage(), e);
        }
    }
}
