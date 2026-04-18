package com.ran.cjb_agent.agent.graph.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ran.cjb_agent.agent.graph.AgentState;
import com.ran.cjb_agent.service.config.ModelConfigStore;
import com.ran.cjb_agent.service.log.AgentInteractionLogger;
import com.ran.cjb_agent.service.os.OsProfileCache;
import com.ran.cjb_agent.websocket.StreamingResponseEmitter;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OS 适配节点
 * LLM 同时输出意图推理步骤 + Shell 命令，推送 THINKING + COMMAND_PREVIEW
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OsAdaptNode {

    private final ModelConfigStore modelConfigStore;
    private final OsProfileCache osProfileCache;
    private final StreamingResponseEmitter emitter;
    private final AgentInteractionLogger interactionLogger;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ADAPT_PROMPT_TEMPLATE = """
            你是一位 Linux 系统管理专家。

            当前服务器环境：
            - 发行版：%s %s
            - 包管理器：%s
            - 服务管理器：%s

            任务意图：%s

            请完成两件事：
            1. 用 4 个步骤推理如何完成该任务（每步一句话）
            2. 给出最终执行的 Shell 命令

            严格按以下 JSON 格式输出（不要包含任何其他内容，不要加 markdown 代码块）：
            {
              "reasoning": [
                "提取动作：...",
                "操作对象：...",
                "核心意图：...",
                "适配方案：..."
              ],
              "command": "实际shell命令"
            }
            """;

    public AgentState process(AgentState state) {
        log.info("OS适配节点 [{}] 步骤{}: {}", state.getSessionId(),
                state.getCurrentTaskIndex() + 1, state.getCurrentTaskDescription());
        emitter.pushNodeProgress(state.getSessionId(), "os_adapt",
                String.format("🔍 正在分析步骤 %d 的执行方案...", state.getCurrentTaskIndex() + 1));

        try {
            var profile = osProfileCache.get(state.getSshConnectionId())
                    .orElse(state.getOsProfile());

            String distroName = "Linux", version = "", pkgManager = "apt", svcManager = "systemctl";
            if (profile != null && profile.isProbeSuccess()) {
                distroName = profile.getDistro().getDisplayName();
                version    = profile.getVersion();
                pkgManager = profile.getCommandMap().getOrDefault("packageManager", "apt");
                svcManager = profile.getCommandMap().getOrDefault("serviceManager", "systemctl");
            }

            String prompt = String.format(ADAPT_PROMPT_TEMPLATE,
                    distroName, version, pkgManager, svcManager,
                    state.getCurrentTaskDescription());

            ChatLanguageModel model = buildModel();
            String raw = model.generate(prompt).trim();

            // Parse JSON response
            ParsedAdapt parsed = parseResponse(raw, state.getCurrentTaskDescription());

            state.setCurrentCommand(parsed.command);
            state.setCurrentNode("os_adapt");

            // Push THINKING with reasoning steps + command
            String thinkingContent = buildThinkingMarkdown(parsed.reasoning);
            emitter.pushThinking(state.getSessionId(), thinkingContent, parsed.command);

            // Record to structured interaction log
            interactionLogger.recordThinking(state.getSessionId(), parsed.reasoning, parsed.command);

            // Keep COMMAND_PREVIEW for security check display
            emitter.pushCommandPreview(state.getSessionId(), parsed.command);

            log.info("OS适配完成 [{}]: {}", state.getSessionId(), parsed.command);

        } catch (Exception e) {
            log.error("OS适配失败 [{}]: {}", state.getSessionId(), e.getMessage());
            state.setHasError(true);
            state.setErrorMessage("命令适配失败: " + e.getMessage());
        }

        return state;
    }

    private ParsedAdapt parseResponse(String raw, String fallbackTask) {
        // Strip possible markdown fences
        String cleaned = raw.replaceAll("```(?:json)?\\n?", "").replaceAll("```", "").trim();

        // Try to extract JSON block
        Pattern jsonPattern = Pattern.compile("\\{[\\s\\S]*}", Pattern.DOTALL);
        Matcher m = jsonPattern.matcher(cleaned);
        if (m.find()) {
            try {
                JsonNode node = MAPPER.readTree(m.group());
                List<String> reasoning = new ArrayList<>();
                if (node.has("reasoning") && node.get("reasoning").isArray()) {
                    node.get("reasoning").forEach(r -> reasoning.add(r.asText()));
                }
                String command = node.has("command") ? node.get("command").asText().trim() : cleaned;
                if (command.isBlank()) command = cleaned;
                return new ParsedAdapt(reasoning, command);
            } catch (Exception ignored) {}
        }

        // Fallback: treat entire response as the command
        return new ParsedAdapt(List.of("分析任务：" + fallbackTask, "生成执行命令"), cleaned);
    }

    private String buildThinkingMarkdown(List<String> steps) {
        StringBuilder sb = new StringBuilder("**意图理解过程：**\n");
        for (String step : steps) {
            sb.append("- ").append(step).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private record ParsedAdapt(List<String> reasoning, String command) {}

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
