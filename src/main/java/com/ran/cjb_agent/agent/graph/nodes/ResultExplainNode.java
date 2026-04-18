package com.ran.cjb_agent.agent.graph.nodes;

import com.ran.cjb_agent.agent.graph.AgentState;
import com.ran.cjb_agent.service.config.ModelConfigStore;
import com.ran.cjb_agent.websocket.StreamingResponseEmitter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * 结果解释节点（流式推送）
 * 纯文本生成，无工具调用，qwen-plus 流式完全兼容
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
        log.info("结果解释节点（流式）[{}] 步骤{}", state.getSessionId(), state.getCurrentTaskIndex() + 1);
        state.setCurrentNode("explain_result");

        String prompt = String.format(EXPLAIN_PROMPT_TEMPLATE,
                state.getCurrentCommand(),
                state.getCurrentTaskDescription(),
                state.getCurrentRawResult());

        StringBuilder accumulated = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        String sessionId = state.getSessionId();

        try {
            StreamingChatLanguageModel model = buildStreamingModel();
            model.generate(prompt, new StreamingResponseHandler<AiMessage>() {
                @Override
                public void onNext(String token) {
                    accumulated.append(token);
                    emitter.pushToken(sessionId, token, false);
                }

                @Override
                public void onComplete(Response<AiMessage> response) {
                    emitter.pushToken(sessionId, "", true);
                    latch.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    log.error("结果解释流式失败 [{}]: {}", sessionId, error.getMessage());
                    String fallback = "执行结果：\n" + state.getCurrentRawResult();
                    accumulated.append(fallback);
                    emitter.pushResult(sessionId, fallback);
                    latch.countDown();
                }
            });

            latch.await();

        } catch (Exception e) {
            log.error("结果解释节点异常 [{}]: {}", sessionId, e.getMessage());
            String fallback = "执行结果：\n" + state.getCurrentRawResult();
            accumulated.append(fallback);
            emitter.pushResult(sessionId, fallback);
        }

        String explained = accumulated.toString();
        state.setCurrentExplainedResult(explained);
        state.addStepResult(explained);

        if (state.hasNextTask()) {
            state.advanceToNextTask();
        }

        return state;
    }

    private StreamingChatLanguageModel buildStreamingModel() {
        var config = modelConfigStore.get();
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();
    }
}
