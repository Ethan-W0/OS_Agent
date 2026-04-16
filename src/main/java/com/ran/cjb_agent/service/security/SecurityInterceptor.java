package com.ran.cjb_agent.service.security;

import com.ran.cjb_agent.model.domain.RiskAssessment;
import com.ran.cjb_agent.model.dto.RiskWarningDto;
import com.ran.cjb_agent.model.enums.RiskLevel;
import com.ran.cjb_agent.websocket.StreamingResponseEmitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 安全风控拦截器（核心模块）
 *
 * 双轨制混合风控架构：
 * ┌─────────────────────────────────────────────────────────────┐
 * │  第一轨：正则兜底（RiskRuleEngine）                           │
 * │  → 仅拦截 Fork Bomb / rm -rf / / dd 系统盘 三类绝对禁止场景 │
 * │                                                             │
 * │  第二轨：LLM 语义评估（SecurityAssessmentAgent）             │
 * │  → 理解用户意图 + 命令上下文，综合判断风险等级               │
 * │  → 保留 AI 自主决策能力，避免退化为传统 WAF                  │
 * └─────────────────────────────────────────────────────────────┘
 *
 * 风险等级处置：
 * SAFE     → 直接执行
 * WARNING  → 附加风险提示后继续执行
 * CRITICAL → 挂起执行，推送警告卡，等待用户二次确认（CompletableFuture）
 * FORBIDDEN → 立即拒绝，生成中文可解释文案，不执行任何 SSH 命令
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityInterceptor {

    private final RiskRuleEngine riskRuleEngine;
    private final ConfirmationManager confirmationManager;
    private final StreamingResponseEmitter streamingEmitter;

    /**
     * 拦截并执行命令（LLM 评估结果版本）
     * 由 LangGraph4j SecurityCheckNode 在获得 LLM 评估结果后调用
     *
     * @param sessionId         会话 ID
     * @param command           待执行命令
     * @param userIntent        用户原始意图描述
     * @param assessment        LLM 安全评估结果
     * @param executionCallback 实际执行逻辑（SSH 命令执行）
     * @return 执行结果或拦截说明
     */
    public String intercept(String sessionId, String command, String userIntent,
                            RiskAssessment assessment, Supplier<String> executionCallback) {

        log.info("安全拦截 [{}] 命令: {} | 评估结果: {}", sessionId, command, assessment.getLevel());

        // ====== 第一道：正则规则兜底（绝对禁止，优先级最高）======
        if (riskRuleEngine.isAbsoluteForbidden(command)) {
            String rationale = riskRuleEngine.generateForbiddenRationale(command);
            log.warn("绝对禁止指令被拦截 [{}]: {}", sessionId, command);
            streamingEmitter.pushRejected(sessionId, command, rationale);
            return formatRejectionMessage(command, rationale);
        }

        // ====== 第二道：按 LLM 评估等级分流处理 ======
        return switch (assessment.getLevel()) {

            case SAFE -> {
                // 安全操作：直接执行，推送命令预览
                streamingEmitter.pushCommandPreview(sessionId, command);
                yield executionCallback.get();
            }

            case WARNING -> {
                // 警告操作：推送风险提示，继续执行
                String warningMsg = buildWarningMessage(command, assessment);
                streamingEmitter.pushWarning(sessionId, warningMsg);
                streamingEmitter.pushCommandPreview(sessionId, command);
                yield executionCallback.get();
            }

            case CRITICAL -> {
                // 高危操作：挂起执行，等待用户二次确认
                String token = UUID.randomUUID().toString();

                log.info("高危操作触发二次确认 [{}] token={}: {}", sessionId, token, command);

                // 向前端推送风险警告卡（含确认按钮 + 倒计时）
                streamingEmitter.pushRiskWarning(sessionId, RiskWarningDto.builder()
                        .level(RiskLevel.CRITICAL)
                        .command(command)
                        .rationale(assessment.getRationale())
                        .suggestedAlternative(assessment.getSuggestedAlternative())
                        .confirmationToken(token)
                        .build());

                // 阻塞当前线程，等待用户响应（CompletableFuture）
                boolean approved = confirmationManager.waitForConfirmation(token);

                if (approved) {
                    log.info("用户批准执行高危操作 [{}]: {}", sessionId, command);
                    streamingEmitter.pushCommandPreview(sessionId, command);
                    yield executionCallback.get();
                } else {
                    String cancelMsg = "🚫 操作已取消。\n用户拒绝或确认超时，高危操作「" + command + "」已熔断阻止。\n" +
                                       "【处置说明】" + assessment.getRationale();
                    log.info("高危操作已熔断 [{}]: {}", sessionId, command);
                    yield cancelMsg;
                }
            }

            case FORBIDDEN -> {
                // 禁止操作：立即拒绝，生成可解释文案
                log.warn("LLM 评估为 FORBIDDEN，拒绝执行 [{}]: {}", sessionId, command);
                streamingEmitter.pushRejected(sessionId, command, assessment.getRationale());
                yield formatRejectionMessage(command, assessment.getRationale());
            }
        };
    }

    /**
     * 快速拦截（仅正则兜底，不经过 LLM，用于工具层直接调用）
     * 适合在 @Tool 方法中对 SAFE 命令的补充校验
     */
    public boolean quickCheck(String command) {
        return !riskRuleEngine.isAbsoluteForbidden(command);
    }

    private String buildWarningMessage(String command, RiskAssessment assessment) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 风险提示\n");
        sb.append("命令：").append(command).append("\n");
        sb.append("评估：").append(assessment.getRationale());
        if (assessment.getSuggestedAlternative() != null && !assessment.getSuggestedAlternative().isBlank()) {
            sb.append("\n💡 建议替代方案：").append(assessment.getSuggestedAlternative());
        }
        sb.append("\n（已继续执行，请注意观察结果）");
        return sb.toString();
    }

    private String formatRejectionMessage(String command, String rationale) {
        return "❌ 操作已永久拒绝\n\n" +
               "命令：" + command + "\n\n" +
               "拒绝原因：\n" + rationale + "\n\n" +
               "如需完成类似操作，请使用更安全的替代方案，或咨询系统管理员。";
    }
}
