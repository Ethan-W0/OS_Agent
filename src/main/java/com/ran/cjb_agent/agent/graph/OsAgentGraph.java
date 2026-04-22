package com.ran.cjb_agent.agent.graph;

import com.ran.cjb_agent.agent.graph.nodes.*;
import com.ran.cjb_agent.model.enums.RiskLevel;
import com.ran.cjb_agent.service.security.SessionContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OsAgentGraph {

    private final IntentParseNode intentParseNode;
    private final OsAdaptNode osAdaptNode;
    private final SecurityCheckNode securityCheckNode;
    private final WaitConfirmNode waitConfirmNode;
    private final ExecutionNode executionNode;
    private final ResultExplainNode resultExplainNode;
    private final SummaryNode summaryNode;
    private final RejectNode rejectNode;

    /**
     * 执行完整的状态图流程
     * 内部实现为显式函数式编排
     *
     * @param initialState 初始状态
     * @return 最终状态
     */
    public AgentState execute(AgentState initialState) {
        AgentState state = initialState;

        log.info("状态图开始执行 [{}]: {}", state.getSessionId(), state.getUserMessage());

        // 将 sessionId 绑定到当前线程，供 @Tool 方法内部使用
        SessionContextHolder.set(state.getSessionId());
        try {
            // ===== 节点1: 意图解析 =====
            state = intentParseNode.process(state);
            if (state.isHasError()) return handleError(state);

            // ===== 多步任务循环 =====
            while (true) {
                // ===== 节点2: OS 适配 =====
                state = osAdaptNode.process(state);
                if (state.isHasError()) return handleError(state);

                // ===== 节点3: 安全检查 =====
                state = securityCheckNode.process(state);

                // ===== 条件路由：安全等级分流 =====
                String route = routeByRiskLevel(state);

                switch (route) {
                    case "reject" -> {
                        state = rejectNode.process(state);
                        return state; // 被拒绝时终止整个流程
                    }
                    case "wait_confirm" -> {
                        state = waitConfirmNode.process(state);
                        if (!state.isUserApproved()) {
                            // 用户拒绝 → 走拒绝节点
                            state = rejectNode.process(state);
                            return state;
                        }
                        // 用户批准 → 继续执行
                        state = executionNode.process(state);
                    }
                    default -> {
                        // SAFE 或 WARNING：直接执行
                        state = executionNode.process(state);
                    }
                }

                // ===== 节点: 结果解释 =====
                state = resultExplainNode.process(state);

                // ===== 条件路由：是否还有下一步？=====
                if (!state.hasNextTask()) {
                    break; // 所有步骤执行完毕，退出循环
                }
                // 有下一步：state.advanceToNextTask() 已在 resultExplainNode 中调用
            }

            // ===== 节点: 汇总 =====
            state = summaryNode.process(state);

        } catch (Exception e) {
            log.error("状态图执行异常 [{}]: {}", state.getSessionId(), e.getMessage(), e);
            state.setHasError(true);
            state.setErrorMessage("Agent 执行异常: " + e.getMessage());
        } finally {
            SessionContextHolder.clear();
        }

        log.info("状态图执行完成 [{}]", state.getSessionId());
        return state;
    }

    /**
     * 根据风险等级决定路由方向
     */
    private String routeByRiskLevel(AgentState state) {
        RiskLevel level = state.getRiskLevel();
        return switch (level) {
            case FORBIDDEN -> "reject";
            case CRITICAL  -> "wait_confirm";
            default        -> "execute"; // SAFE 和 WARNING
        };
    }

    private AgentState handleError(AgentState state) {
        log.error("状态图因错误终止 [{}]: {}", state.getSessionId(), state.getErrorMessage());
        return state;
    }
}
