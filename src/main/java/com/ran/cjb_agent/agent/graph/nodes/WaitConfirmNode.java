package com.ran.cjb_agent.agent.graph.nodes;

import com.ran.cjb_agent.agent.graph.AgentState;
import com.ran.cjb_agent.model.dto.RiskWarningDto;
import com.ran.cjb_agent.model.enums.RiskLevel;
import com.ran.cjb_agent.service.security.ConfirmationManager;
import com.ran.cjb_agent.websocket.StreamingResponseEmitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 等待用户二次确认节点（CRITICAL 级别时触发）
 * 向前端推送风险警告卡，通过 CompletableFuture 挂起图执行
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaitConfirmNode {

    private final ConfirmationManager confirmationManager;
    private final StreamingResponseEmitter emitter;

    public AgentState process(AgentState state) {
        String command = state.getCurrentCommand();
        var assessment = state.getRiskAssessment();

        log.info("等待确认节点 [{}]: {}", state.getSessionId(), command);
        state.setCurrentNode("wait_confirm");

        // 生成唯一确认令牌
        String token = UUID.randomUUID().toString();
        state.setConfirmationToken(token);

        // 向前端推送风险警告卡（含倒计时 + 确认/拒绝按钮）
        emitter.pushRiskWarning(state.getSessionId(), RiskWarningDto.builder()
                .level(RiskLevel.CRITICAL)
                .command(command)
                .rationale(assessment != null ? assessment.getRationale() : "该操作存在较高安全风险，需要您确认后执行。")
                .suggestedAlternative(assessment != null ? assessment.getSuggestedAlternative() : null)
                .confirmationToken(token)
                .timeoutSeconds(120)
                .build());

        log.info("已推送风险确认卡 [{}] token={}", state.getSessionId(), token);

        // ★ 阻塞等待用户响应（CompletableFuture，最长 120 秒）
        boolean approved = confirmationManager.waitForConfirmation(token);
        state.setUserApproved(approved);

        if (approved) {
            log.info("用户批准执行 [{}]: {}", state.getSessionId(), command);
            emitter.pushNodeProgress(state.getSessionId(), "wait_confirm", "✅ 用户已批准，继续执行...");
        } else {
            log.info("用户拒绝或超时 [{}]: {}", state.getSessionId(), command);
            emitter.pushNodeProgress(state.getSessionId(), "wait_confirm", "🚫 用户拒绝或确认超时，操作已取消");
        }

        return state;
    }
}
