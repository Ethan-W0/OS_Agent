package com.ran.cjb_agent.service.agent;

import com.ran.cjb_agent.agent.OsAssistant;
import com.ran.cjb_agent.agent.graph.AgentState;
import com.ran.cjb_agent.agent.graph.OsAgentGraph;
import com.ran.cjb_agent.model.domain.AgentSession;
import com.ran.cjb_agent.model.domain.OsProfile;
import com.ran.cjb_agent.model.enums.AgentStatus;
import com.ran.cjb_agent.service.config.ModelConfigStore;
import com.ran.cjb_agent.service.os.OSProbeService;
import com.ran.cjb_agent.service.os.OsProfileCache;
import com.ran.cjb_agent.service.os.SystemPromptBuilder;
import com.ran.cjb_agent.service.tools.*;
import com.ran.cjb_agent.websocket.StreamingResponseEmitter;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;

/**
 * Agent 核心调度器（最关键的服务类）
 *
 * 职责：
 * 1. 接收用户消息，协调所有服务完成 Agent 执行循环
 * 2. 懒加载 OS 探测，注入动态系统提示词
 * 3. 根据任务复杂度选择执行路径：
 *    - 简单单步任务 → LangChain4j AiServices（工具调用，快速响应）
 *    - 复杂多步任务 → LangGraph4j 状态图（节点编排，完整流程）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final AgentSessionManager sessionManager;
    private final ConversationMemoryStore memoryStore;
    private final OSProbeService osProbeService;
    private final OsProfileCache osProfileCache;
    private final SystemPromptBuilder systemPromptBuilder;
    private final ModelConfigStore modelConfigStore;
    private final StreamingResponseEmitter emitter;
    private final OsAgentGraph osAgentGraph;

    // LangChain4j @Tool 工具类
    private final DiskTools diskTools;
    private final ProcessTools processTools;
    private final FileTools fileTools;
    private final NetworkTools networkTools;
    private final UserTools userTools;
    private final CronTools cronTools;

    /**
     * 异步处理用户消息（@Async 避免阻塞 HTTP 线程，在 agentTaskExecutor 线程池中执行）
     */
    @Async("agentTaskExecutor")
    public void processMessageAsync(String sessionId, String userMessage, String sshConnectionId) {
        log.info("开始处理消息 [{}]: {}", sessionId, userMessage);

        AgentSession session = sessionManager.getOrCreate(sessionId);
        sessionManager.updateStatus(sessionId, AgentStatus.RUNNING);

        if (sshConnectionId != null && !sshConnectionId.isBlank()) {
            sessionManager.bindSshConnection(sessionId, sshConnectionId);
        }

        try {
            // ===== Step 1: 懒加载 OS 探测 =====
            String connId = sshConnectionId != null ? sshConnectionId : session.getBoundSshConnectionId();
            if (connId != null && !osProfileCache.contains(connId)) {
                emitter.pushNodeProgress(sessionId, "os_probe", "🔍 正在探测服务器环境...");
                OsProfile profile = osProbeService.probe(connId);
                session.setOsProfile(profile);
                emitter.pushNodeProgress(sessionId, "os_probe",
                        String.format("✅ 已识别操作系统：%s %s",
                                profile.getDistro().getDisplayName(), profile.getVersion()));
            }

            // ===== Step 2: 选择执行策略 =====
            if (isComplexTask(userMessage)) {
                // 复杂多步任务：使用 LangGraph4j 状态图
                executeWithGraph(sessionId, userMessage, connId, session);
            } else {
                // 简单任务：使用 LangChain4j AiServices（带工具调用）
                executeWithAiServices(sessionId, userMessage, connId, session);
            }

        } catch (Exception e) {
            log.error("消息处理失败 [{}]: {}", sessionId, e.getMessage(), e);
            emitter.pushError(sessionId, "抱歉，执行过程中发生错误：" + e.getMessage());
        } finally {
            sessionManager.updateStatus(sessionId, AgentStatus.IDLE);
        }
    }

    /**
     * 使用 LangGraph4j 状态图处理复杂多步任务
     */
    private void executeWithGraph(String sessionId, String userMessage,
                                  String sshConnectionId, AgentSession session) {
        log.info("使用状态图模式处理 [{}]", sessionId);

        AgentState initialState = AgentState.builder()
                .sessionId(sessionId)
                .userMessage(userMessage)
                .sshConnectionId(sshConnectionId)
                .osProfile(session.getOsProfile())
                .taskList(new ArrayList<>())
                .stepResults(new ArrayList<>())
                .build();

        osAgentGraph.execute(initialState);
    }

    /**
     * 使用 LangChain4j AiServices 处理简单任务（工具调用，同步推送）
     * 使用非流式模型确保工具调用可靠执行，结果推送为完整 TEXT 消息
     */
    private void executeWithAiServices(String sessionId, String userMessage,
                                       String sshConnectionId, AgentSession session) {
        log.info("使用 AiServices 工具调用模式处理 [{}]", sessionId);

        OsProfile profile = session.getOsProfile() != null
                ? session.getOsProfile()
                : osProfileCache.getOrDefault(sshConnectionId);
        String systemPrompt = systemPromptBuilder.buildSystemPrompt(profile);

        ChatMemory memory = memoryStore.getOrCreate(sessionId);
        ChatLanguageModel model = buildCurrentModel();

        OsAssistant assistant = AiServices.builder(OsAssistant.class)
                .chatLanguageModel(model)
                .systemMessageProvider(id -> systemPrompt)
                .chatMemory(memory)
                .tools(diskTools, processTools, fileTools, networkTools, userTools, cronTools)
                .build();

        try {
            String connId = sshConnectionId != null ? sshConnectionId : "";
            emitter.pushNodeProgress(sessionId, "thinking", "🤔 正在分析指令并执行...");
            String result = assistant.chat(connId, userMessage);
            emitter.pushText(sessionId, result);
        } catch (Exception e) {
            log.error("AiServices 执行失败 [{}]: {}", sessionId, e.getMessage());
            emitter.pushError(sessionId, "执行失败：" + e.getMessage());
        }
    }

    /**
     * 判断是否为复杂多步任务（启发式规则）
     */
    private boolean isComplexTask(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        // 包含明显的多步骤关键词
        return lower.contains("然后") || lower.contains("之后") || lower.contains("接着") ||
               lower.contains("如果") || lower.contains("如果不足") || lower.contains("并且") ||
               lower.contains("first") || lower.contains("then") || lower.contains("after") ||
               lower.contains("，再") || lower.contains("，然后") ||
               // 包含条件判断关键词
               lower.contains("如果超过") || lower.contains("如果不够") || lower.contains("检查后");
    }

    /**
     * 基于当前配置动态构建非流式 ChatModel（工具调用更可靠）
     */
    private ChatLanguageModel buildCurrentModel() {
        var config = modelConfigStore.get();
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();
    }
}
