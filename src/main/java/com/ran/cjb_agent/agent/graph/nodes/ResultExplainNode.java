package com.ran.cjb_agent.agent.graph.nodes;

import com.ran.cjb_agent.agent.graph.AgentState;
import com.ran.cjb_agent.service.config.ModelConfigStore;
import com.ran.cjb_agent.websocket.StreamingResponseEmitter;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 结果解释节点
 * 将原始 SSH 命令输出翻译为自然语言并推流到前端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResultExplainNode {

    private final ModelConfigStore modelConfigStore;
    private final StreamingResponseEmitter emitter;

    private static final String EXPLAIN_PROMPT_TEMPLATE = """
            你是一位 Linux 系统管理助手，请将以下命令执行结果翻译为用户友好的中文说明。

            执行的命令：%s
            任务意图：%s
            原始执行结果：
            %s

            要求：
            1. 用简洁、易懂的中文解释执行结果的含义
            2. 提取关键信息（如磁盘使用率、进程数量等）并突出显示
            3. 如果执行成功，说明完成了什么；如果有错误，解释错误原因和建议
            4. 如果结果数据量大，只重点解释最重要的部分
            5. 结尾给出简短的状态总结（一句话）
            """;

    public AgentState process(AgentState state) {
        log.info("结果解释节点 [{}] 步骤{}", state.getSessionId(), state.getCurrentTaskIndex() + 1);
        state.setCurrentNode("explain_result");

        try {
            String prompt = String.format(EXPLAIN_PROMPT_TEMPLATE,
                    state.getCurrentCommand(),
                    state.getCurrentTaskDescription(),
                    state.getCurrentRawResult());

            ChatLanguageModel model = buildModel();
            String explained = model.generate(prompt);

            state.setCurrentExplainedResult(explained);
            state.addStepResult(explained);

            // 推送到前端
            emitter.pushResult(state.getSessionId(), explained);
            log.info("结果解释完成 [{}] 步骤{}", state.getSessionId(), state.getCurrentTaskIndex() + 1);

        } catch (Exception e) {
            log.error("结果解释失败 [{}]: {}", state.getSessionId(), e.getMessage());
            // 降级：直接推送原始结果
            String fallback = "执行结果：\n" + state.getCurrentRawResult();
            state.setCurrentExplainedResult(fallback);
            state.addStepResult(fallback);
            emitter.pushResult(state.getSessionId(), fallback);
        }

        // 如果还有下一步，推进状态
        if (state.hasNextTask()) {
            state.advanceToNextTask();
        }

        return state;
    }

    private ChatLanguageModel buildModel() {
        var config = modelConfigStore.get();
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();
    }
}
