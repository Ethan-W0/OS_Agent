package com.ran.cjb_agent.agent.graph.nodes;

import com.ran.cjb_agent.agent.graph.AgentState;
import com.ran.cjb_agent.service.log.AgentInteractionLogger;
import com.ran.cjb_agent.service.ssh.SshService;
import com.ran.cjb_agent.websocket.StreamingResponseEmitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 命令执行节点
 * 通过 SSH 在目标服务器上执行已通过安全检查的命令
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionNode {

    private final SshService sshService;
    private final StreamingResponseEmitter emitter;
    private final AgentInteractionLogger interactionLogger;

    public AgentState process(AgentState state) {
        String command = state.getCurrentCommand();
        log.info("执行节点 [{}] 步骤{}: {}", state.getSessionId(),
                state.getCurrentTaskIndex() + 1, command);

        emitter.pushNodeProgress(state.getSessionId(), "execute",
                String.format("⚙️ 正在执行步骤 %d...", state.getCurrentTaskIndex() + 1));

        state.setCurrentNode("execute");

        try {
            String rawResult = sshService.execute(state.getSshConnectionId(), command, 60);
            state.setCurrentRawResult(rawResult);
            interactionLogger.recordExecution(state.getSessionId(), command, rawResult);
            log.info("执行完成 [{}] 步骤{}", state.getSessionId(), state.getCurrentTaskIndex() + 1);
        } catch (Exception e) {
            log.error("执行失败 [{}]: {}", state.getSessionId(), e.getMessage());
            state.setCurrentRawResult("❌ 执行异常: " + e.getMessage());
            state.setHasError(true);
            state.setErrorMessage(e.getMessage());
        }

        return state;
    }
}
