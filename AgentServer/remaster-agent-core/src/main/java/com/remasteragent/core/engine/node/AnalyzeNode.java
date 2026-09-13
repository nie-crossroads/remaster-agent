package com.remasteragent.core.engine.node;

import com.remasteragent.common.agent.AnalyzeResult;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeExecutor;
import com.remasteragent.core.engine.NodeOutcome;
import com.remasteragent.tools.ast.JavaSourceAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
            return NodeOutcome.ok(result);
        } catch (IOException e) {
            return NodeOutcome.fail("读取源文件失败: " + e.getMessage());
        } catch (Exception e) {
            // 源码解析不了 —— 说明被测文件本身就是坏的，这属于任务级问题，重试没有意义
            return NodeOutcome.fail("源码无法解析，任务前提不成立: " + e.getMessage());
        }
    }
}
