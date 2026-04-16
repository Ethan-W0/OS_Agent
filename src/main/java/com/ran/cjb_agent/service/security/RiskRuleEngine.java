package com.ran.cjb_agent.service.security;

import com.ran.cjb_agent.model.domain.RiskAssessment;
import com.ran.cjb_agent.model.enums.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 风险规则引擎
 *
 * 设计原则：极简兜底，不做 WAF。
 * 仅拦截"即使 LLM 判断失误也绝对不能执行"的少数极端指令。
 * 绝大多数风险判断委托给 SecurityAssessmentAgent（LLM 语义评估）。
 */
@Slf4j
@Component
public class RiskRuleEngine {

    /**
     * 绝对禁止指令：无论如何都不执行，正则兜底最后一道防线。
     * 仅覆盖三类：Fork Bomb / 删根目录 / dd 覆盖系统盘。
     */
    private static final List<Pattern> ABSOLUTE_FORBIDDEN = List.of(
            // Fork Bomb: :(){ :|:& };:
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{.*\\|.*&.*}\\s*;\\s*:"),
            // rm -rf / 或 rm -rf /*（删除根目录）
            Pattern.compile("rm\\s+(-[a-zA-Z]*f[a-zA-Z]*r[a-zA-Z]*|-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*)\\s+/\\s*$"),
            Pattern.compile("rm\\s+(-[a-zA-Z]*f[a-zA-Z]*r[a-zA-Z]*|-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*)\\s+/\\*"),
            // dd 直接覆盖系统磁盘（/dev/sda、/dev/nvme0n1 等）
            Pattern.compile("dd\\s+if=/dev/(zero|random|urandom)\\s+of=/dev/(s|h|vd|nvm)[a-z0-9]+\\b")
    );

    /**
     * 快速前置检查：是否触发绝对禁止规则。
     * true = 立即拒绝，不交给 LLM 评估。
     */
    public boolean isAbsoluteForbidden(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.trim().replaceAll("\\s+", " ");
        for (Pattern pattern : ABSOLUTE_FORBIDDEN) {
            if (pattern.matcher(normalized).find()) {
                log.warn("触发绝对禁止规则: {}", normalized);
                return true;
            }
        }
        return false;
    }

    /**
     * 生成绝对禁止指令的拒绝理由（面向用户的中文可解释说明）
     */
    public String generateForbiddenRationale(String command) {
        String normalized = command.trim();

        if (normalized.contains(":(){ :|:") || normalized.matches(".*:\\s*\\(\\s*\\).*")) {
            return "检测到 Fork Bomb 攻击指令（" + normalized + "）。\n" +
                   "该命令通过递归无限 fork 子进程，将在毫秒内耗尽服务器所有进程资源，" +
                   "导致系统完全失去响应，只能通过强制重启恢复。\n" +
                   "【拒绝依据】此类指令属于系统性破坏攻击，无任何合法使用场景，已被永久拦截。";
        }

        if (normalized.matches(".*rm.*-.*rf.*[/].*") || normalized.matches(".*rm.*[/].*-.*rf.*")) {
            return "检测到对根目录（/）的强制递归删除操作（rm -rf /）。\n" +
                   "该操作将删除操作系统所有文件，包括内核、引导程序、系统库等关键组件，" +
                   "执行后服务器将立即且永久不可用，无法通过软件手段恢复。\n" +
                   "【拒绝依据】此操作具有不可逆的系统毁灭性，已被永久拦截。";
        }

        if (normalized.contains("dd") && normalized.contains("/dev/")) {
            return "检测到使用 dd 命令向物理磁盘设备直接写入零字节/随机数据的操作。\n" +
                   "该操作将覆盖磁盘所有数据（包括分区表、文件系统、操作系统），" +
                   "导致数据永久丢失且系统无法启动。\n" +
                   "【拒绝依据】此操作具有不可逆的数据毁灭性，已被永久拦截。";
        }

        return "该指令（" + normalized + "）被安全系统识别为可能导致服务器完全不可用的极端危险操作，已被永久拦截执行。\n" +
               "【拒绝依据】根据最小破坏原则，任何可能造成系统不可逆损害的操作均在拦截范围内。";
    }

    /**
     * 判断命令是否为纯只读命令（可跳过 LLM 评估，直接放行）
     * 节省 LLM Token，提升响应速度
     */
    public boolean isClearlyReadOnly(String command) {
        if (command == null || command.isBlank()) return true;
        String normalized = command.trim().toLowerCase();
        return normalized.startsWith("cat ") ||
               normalized.startsWith("ls ") || normalized.equals("ls") ||
               normalized.startsWith("df ") || normalized.equals("df") ||
               normalized.startsWith("du ") ||
               normalized.startsWith("ps ") ||
               normalized.startsWith("top ") ||
               normalized.startsWith("free ") ||
               normalized.startsWith("uname ") ||
               normalized.startsWith("whoami") ||
               normalized.startsWith("uptime") ||
               normalized.startsWith("hostname") ||
               normalized.startsWith("echo ") ||
               normalized.startsWith("date") ||
               normalized.startsWith("ss ") ||
               normalized.startsWith("netstat ") ||
               normalized.startsWith("grep ") ||
               normalized.startsWith("find ") ||
               normalized.startsWith("which ") ||
               normalized.startsWith("type ") ||
               normalized.startsWith("head ") ||
               normalized.startsWith("tail ") ||
               normalized.startsWith("wc ") ||
               normalized.startsWith("stat ");
    }

    /**
     * 从 LLM 返回的 JSON 字符串中解析 RiskAssessment
     * LLM 格式: {"level":"CRITICAL","rationale":"...","suggestedAlternative":"..."}
     */
    public RiskAssessment parseFromLlmJson(String command, String llmJson) {
        try {
            // 简单解析（避免引入额外依赖）
            String level = extractJsonField(llmJson, "level");
            String rationale = extractJsonField(llmJson, "rationale");
            String suggested = extractJsonField(llmJson, "suggestedAlternative");

            RiskLevel riskLevel = RiskLevel.valueOf(level.toUpperCase());
            return RiskAssessment.builder()
                    .level(riskLevel)
                    .command(command)
                    .rationale(rationale)
                    .suggestedAlternative(suggested)
                    .build();
        } catch (Exception e) {
            log.warn("LLM 安全评估结果解析失败，默认为 WARNING: {}", llmJson);
            return RiskAssessment.warning(command, "安全评估结果解析异常，建议谨慎确认后执行。");
        }
    }

    private String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return "";
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return "";
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return "";
        int end = json.indexOf('"', start + 1);
        if (end < 0) return "";
        return json.substring(start + 1, end);
    }
}
