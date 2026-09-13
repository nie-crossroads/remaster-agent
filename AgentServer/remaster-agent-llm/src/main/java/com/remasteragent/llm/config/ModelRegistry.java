package com.remasteragent.llm.config;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型路由 —— 按用途挑模型。
 *
 * <p>存在的理由很实在：<b>成本</b>。一条迁移链路上 REWRITE 节点会被调用几十次
 * （每个目标文件一次，每次回退重写再来一次），而 ANALYZE 只在开头跑一次。
 * 如果所有节点都走同一个高档模型，改写节点会吃掉绝大部分成本，
 * 而改写恰恰是难度最低、最适合用小模型的环节。
 *
 * <p>这个设计同时回答了面试里的一个高频追问：「你成本怎么控的？」
 * 答案不应该是「我选了个便宜的模型」，而是「我按节点频次与难度分层路由，并用 llm_call 表实测了分层前后的差异」。
 */
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final ChatModel defaultModel;
    private final ChatModel rewriteModel;
    private final String defaultModelName;
    private final String rewriteModelName;

    public ModelRegistry(ChatModel defaultModel, String defaultModelName,
                         ChatModel rewriteModel, String rewriteModelName) {
        this.defaultModel = defaultModel;
        this.defaultModelName = defaultModelName;
        this.rewriteModel = rewriteModel;
        this.rewriteModelName = rewriteModelName;

        if (!defaultModelName.equals(rewriteModelName)) {
            log.info("多模型路由已启用: 默认={} 改写={}", defaultModelName, rewriteModelName);
        } else {
            log.info("多模型路由未启用（改写与默认同一模型）: {}", defaultModelName);
        }
    }

    /** 按用途取模型。 */
    public ChatModel forPurpose(LlmPurpose purpose) {
        return purpose == LlmPurpose.REWRITE ? rewriteModel : defaultModel;
    }

    /** 按用途取模型名，用于写 llm_call 表与日志。 */
    public String modelNameForPurpose(LlmPurpose purpose) {
        return purpose == LlmPurpose.REWRITE ? rewriteModelName : defaultModelName;
    }

    public String defaultModelName() {
        return defaultModelName;
    }

    public String rewriteModelName() {
        return rewriteModelName;
    }
}
