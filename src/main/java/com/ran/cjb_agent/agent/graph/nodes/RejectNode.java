package com.ran.cjb_agent.agent.graph.nodes;

import com.ran.cjb_agent.agent.graph.AgentState;
import com.ran.cjb_agent.websocket.StreamingResponseEmitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 拒绝节点
 * 操作被 FORBIDDEN 或用户拒绝时执行，推送拒绝通知并记录审计日志
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RejectNode {

    private final StreamingResponseEmitter emitter;

    public AgentState process(AgentState state) {
        String command = state.getCurrentCommand();
        var assessment = state.getRiskAssessment();
        boolean userRejected = !state.isUserApproved() && state.getConfirmationToken() != null;

        log.warn("拒绝节点 [{}] 原因: {} | 命令: {}",
                state.getSessionId(),
                userRejected ? "用户拒绝/超时" : "安全规则禁止",
                command);

        state.setCurrentNode("reject");

        String rationale = (assessment != null && assessment.getRationale() != null)
                ? assessment.getRationale()
                : "该操作被安全策略拒绝。";

        if (userRejected) {
            // 用户主动拒绝或超时
            String msg = String.format(
                    "🚫 操作已取消\n\n命令：%s\n\n【处置说明】%s\n\n您可以根据实际需求调整操作方式后重新尝试。",
                    command, rationale);
            emitter.pushText(state.getSessionId(), msg);
            state.addStepResult("用户取消操作：" + command);
        } else {
            // 安全规则拒绝（FORBIDDEN）
            emitter.pushRejected(state.getSessionId(), command, rationale);
            state.addStepResult("安全拒绝：" + command + "（" + rationale + "）");
        }

        // 拒绝后不继续后续步骤
        state.setTaskList(java.util.List.of());
        return state;
    }
}
