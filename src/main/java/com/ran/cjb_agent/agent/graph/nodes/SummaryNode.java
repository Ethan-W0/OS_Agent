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
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 汇总节点（多步任务流式推送）
 * 纯文本生成，无工具调用，qwen-plus 流式完全兼容
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryNode {

    private final ModelConfigStore modelConfigStore;
    private final StreamingResponseEmitter emitter;

    public AgentState process(AgentState state) {
        log.info("汇总节点 [{}]: 共{}步", state.getSessionId(), state.getTaskList().size());
        state.setCurrentNode("summary");

        List<String> stepResults = state.getStepResults();

        // 单步任务：结果已由 ResultExplainNode 流式推送，无需额外汇总
        if (state.getTaskList().size() <= 1) {
            emitter.pushNodeProgress(state.getSessionId(), "summary", "✅ 任务执行完成");
            state.setFinalSummary(stepResults.isEmpty() ? "任务已完成。" : stepResults.get(0));
            return state;
        }

        // 多步任务：流式生成整体执行报告
        StringBuilder stepsText = new StringBuilder();
        for (int i = 0; i < stepResults.size(); i++) {
            stepsText.append(String.format("\n【步骤 %d】%s\n", i + 1, stepResults.get(i)));
        }

        String prompt = String.format("""
                以下是一个多步骤任务的执行结果，请生成一份简洁的整体执行报告：

                原始用户指令：%s

                各步骤执行结果：%s

                请生成：
                1. 任务整体完成状态（成功/部分成功/失败）
                2. 各步骤关键结论（一句话）
                3. 最终状态总结
                4. 如有需要，给出后续建议
                """, state.getUserMessage(), stepsText);

        StringBuilder accumulated = new StringBuilder("\n📋 **任务执行报告**\n\n");
        CountDownLatch latch = new CountDownLatch(1);
        String sessionId = state.getSessionId();

        // Push header first
        emitter.pushToken(sessionId, "\n📋 **任务执行报告**\n\n", false);

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
                    log.error("汇总流式失败 [{}]: {}", sessionId, error.getMessage());
                    String fallback = String.format("✅ 任务执行完成，共 %d 个步骤。", stepResults.size());
                    accumulated.append(fallback);
                    emitter.pushResult(sessionId, fallback);
                    latch.countDown();
                }
            });

            latch.await();

        } catch (Exception e) {
            log.error("汇总节点异常 [{}]: {}", sessionId, e.getMessage());
            String fallback = String.format("✅ 任务执行完成，共 %d 个步骤。", stepResults.size());
            accumulated.append(fallback);
            emitter.pushResult(sessionId, fallback);
        }

        state.setFinalSummary(accumulated.toString());
        emitter.pushNodeProgress(sessionId, "summary", "✅ 所有任务执行完成");
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
