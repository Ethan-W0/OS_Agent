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
import java.util.List;

/**
 * 汇总节点
 * 在所有步骤执行完毕后，生成整体执行摘要
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

        // 单步任务：结果已由 ResultExplainNode 推送，无需额外汇总
        if (state.getTaskList().size() <= 1) {
            emitter.pushNodeProgress(state.getSessionId(), "summary", "✅ 任务执行完成");
            state.setFinalSummary(stepResults.isEmpty() ? "任务已完成。" : stepResults.get(0));
            return state;
        }

        // 多步任务：生成整体执行报告
        try {
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

            ChatLanguageModel model = buildModel();
            String summary = model.generate(prompt);

            state.setFinalSummary(summary);
            emitter.pushText(state.getSessionId(), "\n📋 **任务执行报告**\n\n" + summary);

        } catch (Exception e) {
            log.error("汇总失败 [{}]: {}", state.getSessionId(), e.getMessage());
            String fallback = String.format("✅ 任务执行完成，共 %d 个步骤。", stepResults.size());
            state.setFinalSummary(fallback);
            emitter.pushText(state.getSessionId(), fallback);
        }

        emitter.pushNodeProgress(state.getSessionId(), "summary", "✅ 所有任务执行完成");
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
