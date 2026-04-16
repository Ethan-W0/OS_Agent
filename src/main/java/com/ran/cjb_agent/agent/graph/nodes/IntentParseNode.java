package com.ran.cjb_agent.agent.graph.nodes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ran.cjb_agent.agent.graph.AgentState;
import com.ran.cjb_agent.service.config.ModelConfigStore;
import com.ran.cjb_agent.websocket.StreamingResponseEmitter;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 意图解析节点
 * 将用户的自然语言指令解析为结构化的多步任务列表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentParseNode {

    private final ModelConfigStore modelConfigStore;
    private final StreamingResponseEmitter emitter;
    private final ObjectMapper objectMapper;

    private static final String PARSE_PROMPT_TEMPLATE = """
            请将以下用户指令分解为一个或多个具体的执行步骤。

            用户指令：%s

            要求：
            1. 每个步骤描述一个独立的操作意图（不是具体命令，而是自然语言描述的操作目标）
            2. 步骤之间有逻辑依赖时，请保持正确顺序
            3. 单步操作直接输出只含一个元素的数组

            输出格式：严格输出 JSON 字符串数组，不要有其他内容。
            示例：["查询磁盘使用情况", "如果磁盘使用率超过80%%则清理/tmp目录", "重新查询磁盘空间确认效果"]
            """;

    public AgentState process(AgentState state) {
        log.info("意图解析节点 [{}]: {}", state.getSessionId(), state.getUserMessage());
        emitter.pushNodeProgress(state.getSessionId(), "intent_parse", "正在解析您的指令...");

        try {
            ChatLanguageModel model = buildModel();
            String prompt = String.format(PARSE_PROMPT_TEMPLATE, state.getUserMessage());
            String response = model.generate(prompt);

            // 解析 JSON 数组
            List<String> taskList = parseTaskList(response, state.getUserMessage());

            state.setTaskList(taskList);
            state.setCurrentTaskIndex(0);
            state.setCurrentTaskDescription(taskList.get(0));
            state.setCurrentNode("intent_parse");

            log.info("意图解析完成 [{}]: {}个步骤", state.getSessionId(), taskList.size());
            emitter.pushNodeProgress(state.getSessionId(), "intent_parse",
                    String.format("✅ 已解析为 %d 个执行步骤", taskList.size()));

        } catch (Exception e) {
            log.error("意图解析失败 [{}]: {}", state.getSessionId(), e.getMessage());
            // 解析失败时，将整个用户消息作为单步任务
            state.setTaskList(List.of(state.getUserMessage()));
            state.setCurrentTaskIndex(0);
            state.setCurrentTaskDescription(state.getUserMessage());
        }

        return state;
    }

    private List<String> parseTaskList(String jsonResponse, String fallback) {
        try {
            // 提取 JSON 数组部分
            String cleaned = jsonResponse.trim();
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
            List<String> tasks = objectMapper.readValue(cleaned, new TypeReference<>() {});
            if (tasks != null && !tasks.isEmpty()) {
                return tasks;
            }
        } catch (Exception e) {
            log.warn("JSON 解析失败，使用单步降级: {}", e.getMessage());
        }
        return new ArrayList<>(List.of(fallback));
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
