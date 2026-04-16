package com.ran.cjb_agent.agent.graph.nodes;

import com.ran.cjb_agent.agent.graph.AgentState;
import com.ran.cjb_agent.service.config.ModelConfigStore;
import com.ran.cjb_agent.service.os.OsProfileCache;
import com.ran.cjb_agent.websocket.StreamingResponseEmitter;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * OS 适配节点
 * 将抽象的任务意图映射为适合当前发行版的具体 Shell 命令
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OsAdaptNode {

    private final ModelConfigStore modelConfigStore;
    private final OsProfileCache osProfileCache;
    private final StreamingResponseEmitter emitter;

    private static final String ADAPT_PROMPT_TEMPLATE = """
            你是一位 Linux 系统管理专家。

            当前服务器环境：
            - 发行版：%s %s
            - 包管理器：%s
            - 服务管理器：%s

            任务意图：%s

            请将上述任务意图转换为一条具体的 Shell 命令（或管道组合命令）。

            要求：
            1. 使用该发行版的原生命令和推荐工具
            2. 命令必须安全，避免破坏性操作
            3. 只输出命令本身，不要有任何解释、注释或 Markdown 格式
            4. 命令中如果需要交互输入，请使用非交互方式（如 -y、-f 等参数）

            只输出命令：
            """;

    public AgentState process(AgentState state) {
        log.info("OS适配节点 [{}] 步骤{}: {}", state.getSessionId(),
                state.getCurrentTaskIndex() + 1, state.getCurrentTaskDescription());
        emitter.pushNodeProgress(state.getSessionId(), "os_adapt",
                String.format("🔧 正在为步骤 %d 生成适配命令...", state.getCurrentTaskIndex() + 1));

        try {
            var profile = osProfileCache.get(state.getSshConnectionId())
                    .orElse(state.getOsProfile());

            String distroName = "Linux";
            String version = "";
            String pkgManager = "apt";
            String svcManager = "systemctl";

            if (profile != null && profile.isProbeSuccess()) {
                distroName = profile.getDistro().getDisplayName();
                version = profile.getVersion();
                pkgManager = profile.getCommandMap().getOrDefault("packageManager", "apt");
                svcManager = profile.getCommandMap().getOrDefault("serviceManager", "systemctl");
            }

            String prompt = String.format(ADAPT_PROMPT_TEMPLATE,
                    distroName, version, pkgManager, svcManager,
                    state.getCurrentTaskDescription());

            ChatLanguageModel model = buildModel();
            String command = model.generate(prompt).trim();

            // 清理 LLM 可能加的代码块标记
            command = command.replaceAll("```(?:bash|shell|sh)?\\n?", "")
                             .replaceAll("```", "")
                             .trim();

            state.setCurrentCommand(command);
            state.setCurrentNode("os_adapt");

            log.info("OS适配完成 [{}]: {}", state.getSessionId(), command);
            emitter.pushCommandPreview(state.getSessionId(), command);

        } catch (Exception e) {
            log.error("OS适配失败 [{}]: {}", state.getSessionId(), e.getMessage());
            state.setHasError(true);
            state.setErrorMessage("命令适配失败: " + e.getMessage());
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
