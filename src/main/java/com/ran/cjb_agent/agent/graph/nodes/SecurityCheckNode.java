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

        // ══ 第1层：绝对禁止（系统毁灭性操作）══════════════════════════
        if (riskRuleEngine.isAbsoluteForbidden(command)) {
            return applyAssessment(state, RiskAssessment.forbidden(
                    command, riskRuleEngine.generateForbiddenRationale(command)), "绝对禁止");
        }

        // ══ 第2层：命令注入 / 恶意内容特征 ═══════════════════════════
        if (riskRuleEngine.isCommandInjection(command)) {
            return applyAssessment(state, RiskAssessment.forbidden(command,
                    "命令中包含命令注入特征（`;`、反弹 Shell、挖矿脚本等恶意模式），" +
                    "已识别为安全威胁，拒绝执行。请使用安全的等效命令替代。"), "命令注入");
        }

        // ══ 第3层：只读命令白名单 ══════════════════════════════════════
        if (riskRuleEngine.isClearlyReadOnly(command)) {
            return applyAssessment(state, RiskAssessment.safe(command), "只读放行");
        }

        // ══ 第4层：写操作路径分析（核心新逻辑）══════════════════════
        if (riskRuleEngine.isWriteOperation(command)) {
            RiskAssessment pathAssessment = riskRuleEngine.assessByPathAndContent(command);
            if (pathAssessment != null) {
                emitter.pushNodeProgress(state.getSessionId(), "security_check",
                        String.format("✅ 路径分析完成，风险等级：%s", pathAssessment.getLevel().getDisplayName()));
                return applyAssessment(state, pathAssessment, "路径分析");
            }
            // 路径不明确，降级交 LLM
        }

        // ══ 第5层：LLM 语义评估（兜底）══════════════════════════════
        try {
            String context = String.format("用户意图：%s\n待执行命令：%s",
                    state.getCurrentTaskDescription(), command);

            ChatLanguageModel model = buildModel();
            String llmResponse = model.generate(
                    dev.langchain4j.data.message.SystemMessage.from(
                            SystemPromptBuilder.buildSecurityAssessmentPrompt()),
                    dev.langchain4j.data.message.UserMessage.from(context)
            ).content().text();

            RiskAssessment assessment = riskRuleEngine.parseFromLlmJson(command, llmResponse);
            log.info("LLM 安全评估完成 [{}]: {} → {}", state.getSessionId(), command, assessment.getLevel());
            emitter.pushNodeProgress(state.getSessionId(), "security_check",
                    String.format("✅ 安全评估完成，风险等级：%s", assessment.getLevel().getDisplayName()));
            return applyAssessment(state, assessment, "LLM评估");

        } catch (Exception e) {
            log.error("LLM 安全评估失败 [{}]: {}", state.getSessionId(), e.getMessage());
            return applyAssessment(state,
                    RiskAssessment.warning(command, "安全评估服务暂时不可用，建议人工确认后执行。"),
                    "LLM失败降级");
        }
    }

    private AgentState applyAssessment(AgentState state, RiskAssessment assessment, String source) {
        state.setRiskAssessment(assessment);
        state.setRiskLevel(assessment.getLevel());
        log.info("安全检查结果 [{}][{}]: {} → {}", state.getSessionId(), source,
                state.getCurrentCommand(), assessment.getLevel());
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
