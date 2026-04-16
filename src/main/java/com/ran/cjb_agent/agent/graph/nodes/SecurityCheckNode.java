package com.ran.cjb_agent.agent.graph.nodes;

import com.ran.cjb_agent.agent.graph.AgentState;
import com.ran.cjb_agent.model.domain.RiskAssessment;
import com.ran.cjb_agent.model.enums.RiskLevel;
import com.ran.cjb_agent.service.config.ModelConfigStore;
import com.ran.cjb_agent.service.security.RiskRuleEngine;
import com.ran.cjb_agent.service.os.SystemPromptBuilder;
import com.ran.cjb_agent.websocket.StreamingResponseEmitter;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 安全检查节点（LangGraph4j 节点）
 *
 * 双轨制风控实现：
 * 1. 正则规则兜底（RiskRuleEngine）- 极少数绝对禁止场景
 * 2. LLM 语义评估 - 大多数风险判断，保留 AI 自主理解能力
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityCheckNode {

    private final RiskRuleEngine riskRuleEngine;
    private final ModelConfigStore modelConfigStore;
    private final StreamingResponseEmitter emitter;

    public AgentState process(AgentState state) {
        String command = state.getCurrentCommand();
        log.info("安全检查节点 [{}]: {}", state.getSessionId(), command);
        emitter.pushNodeProgress(state.getSessionId(), "security_check", "🔍 正在进行安全评估...");

        state.setCurrentNode("security_check");

        // ===== 第一道：正则兜底（绝对禁止，快速拦截）=====
        if (riskRuleEngine.isAbsoluteForbidden(command)) {
            String rationale = riskRuleEngine.generateForbiddenRationale(command);
            RiskAssessment assessment = RiskAssessment.forbidden(command, rationale);
            state.setRiskAssessment(assessment);
            state.setRiskLevel(RiskLevel.FORBIDDEN);
            log.warn("安全检查：绝对禁止指令 [{}]: {}", state.getSessionId(), command);
            return state;
        }

        // ===== 只读命令快速放行（节省 LLM Token）=====
        if (riskRuleEngine.isClearlyReadOnly(command)) {
            state.setRiskAssessment(RiskAssessment.safe(command));
            state.setRiskLevel(RiskLevel.SAFE);
            log.debug("安全检查：只读命令直接放行 [{}]: {}", state.getSessionId(), command);
            return state;
        }

        // ===== 第二道：LLM 语义评估（保留 AI 自主理解）=====
        try {
            String context = String.format(
                    "用户意图：%s\n待执行命令：%s",
                    state.getCurrentTaskDescription(), command
            );

            ChatLanguageModel model = buildModel();
            String systemPrompt = SystemPromptBuilder.buildSecurityAssessmentPrompt();
            String llmResponse = model.generate(
                    dev.langchain4j.data.message.SystemMessage.from(systemPrompt),
                    dev.langchain4j.data.message.UserMessage.from(context)
            ).content().text();

            RiskAssessment assessment = riskRuleEngine.parseFromLlmJson(command, llmResponse);
            state.setRiskAssessment(assessment);
            state.setRiskLevel(assessment.getLevel());

            log.info("安全检查完成 [{}]: {} → {}", state.getSessionId(), command, assessment.getLevel());
            emitter.pushNodeProgress(state.getSessionId(), "security_check",
                    String.format("✅ 安全评估完成，风险等级：%s", assessment.getLevel().getDisplayName()));

        } catch (Exception e) {
            log.error("LLM 安全评估失败 [{}]: {}", state.getSessionId(), e.getMessage());
            // 评估失败时保守处理，降级为 WARNING
            state.setRiskAssessment(RiskAssessment.warning(command, "安全评估服务暂时不可用，建议人工确认后执行。"));
            state.setRiskLevel(RiskLevel.WARNING);
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
