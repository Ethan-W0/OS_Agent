package com.ran.cjb_agent.service.tools;

import com.ran.cjb_agent.model.dto.RiskWarningDto;
import com.ran.cjb_agent.model.enums.RiskLevel;
import com.ran.cjb_agent.service.os.OsProfileCache;
import com.ran.cjb_agent.service.security.ConfirmationManager;
import com.ran.cjb_agent.service.security.SessionContextHolder;
import com.ran.cjb_agent.service.ssh.SshService;
import com.ran.cjb_agent.websocket.StreamingResponseEmitter;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommandTools {

    private final SshService sshService;
    private final ConfirmationManager confirmationManager;
    private final OsProfileCache osProfileCache;
    private final StreamingResponseEmitter emitter;

    /** 高危命令关键字，命中则强制走用户确认流程 */
    private static final Set<String> HIGH_RISK_KEYWORDS = Set.of(
            "rm ", "rm\t", "mkfs", "dd ", "dd\t", "chmod", "chown",
            "kill ", "kill\t", "pkill", "shutdown", "reboot", "halt",
            "iptables", "ufw", "firewall", "passwd", "userdel", "groupdel",
            "truncate", "shred", "> /dev/", "format"
    );

    @Tool("执行自定义Shell命令。当内置工具不满足需求时使用，LLM根据目标系统OS/发行版自行生成最优命令。" +
            "高风险命令（含rm/kill/chmod/chown等）执行前须用户确认。")
    public String commandExecute(
            @P("SSH连接ID") String sshConnectionId,
            @P("要执行的完整Shell命令，由LLM根据目标系统发行版和需求生成") String command,
            @P("命令风险等级：LOW/MEDIUM/HIGH/CRITICAL，影响是否弹出确认框") String riskLevel,
            @P("命令的用途说明，向用户解释为什么执行这条命令") String rationale
    ) {
        if (command == null || command.isBlank()) {
            return "请提供要执行的命令。";
        }

        // 超危命令直接拦截，不允许执行
        if (isForbidden(command)) {
            return "命令被系统策略拒绝，禁止在 Agent 中执行此类高破坏性命令，请手动操作：\n" + command;
        }

        // 判断是否需要用户二次确认
        boolean needsConfirmation = isHighRisk(riskLevel) || containsHighRiskKeyword(command);

        if (needsConfirmation) {
            String sessionId = SessionContextHolder.get();
            String token = UUID.randomUUID().toString();

            RiskLevel level = parseRiskLevel(riskLevel);
            emitter.pushRiskWarning(sessionId, RiskWarningDto.builder()
                    .level(level)
                    .command(command)
                    .rationale(rationale != null ? rationale : "即将执行高危命令，请确认是否继续。")
                    .confirmationToken(token)
                    .timeoutSeconds(120)
                    .build());

            log.warn("高危命令等待用户确认 [token={}] cmd={}", token, command);
            boolean approved = confirmationManager.waitForConfirmation(token);
            if (!approved) {
                return "🚫 用户已取消操作，命令未执行：\n" + command;
            }
        }

        // 执行命令，超时根据风险等级适当延长
        int timeoutSeconds = isHighRisk(riskLevel) ? 60 : 30;
        log.info("执行自定义命令 [conn={}] cmd={}", sshConnectionId, command);
        String result = sshService.execute(sshConnectionId, command, timeoutSeconds);

        if (result == null || result.isBlank()) {
            return "✅ 命令执行完成，无输出。\n命令：" + command;
        }
        return String.format("【命令执  行结果】\n命令：%s\n%s", command, result);
    }

    // ----------------------------------------------------------------

    private boolean isForbidden(String command) {
        String lower = command.toLowerCase();
        // 绝对禁止：对根目录 / 或关键系统目录做递归删除/格式化
        return lower.matches(".*rm\\s+-rf\\s+/\\s*.*")
                || lower.matches(".*rm\\s+-rf\\s+/etc.*")
                || lower.matches(".*rm\\s+-rf\\s+/boot.*")
                || lower.matches(".*mkfs.*")
                || lower.matches(".*dd\\s+if=.*of=/dev/[shv]d[a-z].*");
    }

    private boolean containsHighRiskKeyword(String command) {
        String lower = command.toLowerCase();
        return HIGH_RISK_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private boolean isHighRisk(String riskLevel) {
        if (riskLevel == null) return false;
        String upper = riskLevel.toUpperCase();
        return upper.equals("HIGH") || upper.equals("CRITICAL");
    }

    private RiskLevel parseRiskLevel(String riskLevel) {
        if (riskLevel == null) return RiskLevel.WARNING;
        return switch (riskLevel.toUpperCase()) {
            case "CRITICAL" -> RiskLevel.CRITICAL;
            case "HIGH"     -> RiskLevel.CRITICAL;
            case "MEDIUM"   -> RiskLevel.WARNING;
            default         -> RiskLevel.WARNING;
        };
    }
}
