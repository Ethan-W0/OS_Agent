package com.ran.cjb_agent.agent.graph.nodes;

import com.ran.cjb_agent.agent.graph.AgentState;
import com.ran.cjb_agent.service.log.AgentInteractionLogger;
import com.ran.cjb_agent.service.ssh.SshService;
import com.ran.cjb_agent.websocket.StreamingResponseEmitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 命令执行节点
 * 通过 SSH 在目标服务器上执行已通过安全检查的命令。
 * 对需要 root 权限的命令自动通过 executeWithSudo 执行
 * （若没有可用密码，会向前端推送 SUDO_REQUEST 请求用户输入密码）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionNode {

    private final SshService sshService;
    private final StreamingResponseEmitter emitter;
    private final AgentInteractionLogger interactionLogger;

    /** 需要 root 权限的命令关键词（含管道/链式命令中的子命令） */
    private static final List<String> PRIVILEGED_KEYWORDS = List.of(
            "useradd", "userdel", "usermod",
            "groupadd", "groupdel", "groupmod",
            "chpasswd", "passwd ",
            "apt ", "apt-get ", "yum ", "dnf ", "zypper ", "pacman ",
            "systemctl enable", "systemctl disable", "systemctl mask",
            "systemctl start", "systemctl stop", "systemctl restart",
            "mkfs", "mount ", "umount ",
            "iptables", "ufw ",
            "chown ", "chmod "
    );

    public AgentState process(AgentState state) {
        String command = state.getCurrentCommand();
        log.info("执行节点 [{}] 步骤{}: {}", state.getSessionId(),
                state.getCurrentTaskIndex() + 1, command);

        emitter.pushNodeProgress(state.getSessionId(), "execute",
                String.format("⚙️ 正在执行步骤 %d...", state.getCurrentTaskIndex() + 1));

        state.setCurrentNode("execute");

        try {
            String rawResult = requiresPrivilege(command)
                    ? sshService.executeWithSudo(state.getSshConnectionId(), command, 120)
                    : sshService.execute(state.getSshConnectionId(), command, 60);

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

    /**
     * 判断命令（含 && / | / ; 连接的复合命令）是否需要 root 权限
     */
    private boolean requiresPrivilege(String command) {
        if (command == null || command.isBlank()) return false;
        // Split on common shell separators and check each sub-command
        String[] parts = command.split("&&|\\|\\|?|;");
        for (String part : parts) {
            String trimmed = part.trim().toLowerCase();
            for (String kw : PRIVILEGED_KEYWORDS) {
                if (trimmed.startsWith(kw.toLowerCase())) return true;
            }
        }
        return false;
    }
}
