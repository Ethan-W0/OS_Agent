package com.ran.cjb_agent.service.security;

import com.ran.cjb_agent.model.domain.RiskAssessment;
import com.ran.cjb_agent.model.enums.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分级风控规则引擎（重构版）
 *
 * 三层决策树：
 *  1. 高危规则（正则）→ FORBIDDEN（不经 LLM）
 *  2. 路径/内容分析  → SAFE / WARNING / CRITICAL（不经 LLM）
 *  3. 其他           → 交 LLM 语义评估
 */
@Slf4j
@Component
public class RiskRuleEngine {

    // ══════════════════════════════════════════════════════════════════
    // 1. 绝对禁止：系统毁灭性操作
    // ══════════════════════════════════════════════════════════════════

    private static final List<Pattern> ABSOLUTE_FORBIDDEN = List.of(
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{.*\\|.*&.*}\\s*;\\s*:"),
            Pattern.compile("rm\\s+(-[a-zA-Z]*f[a-zA-Z]*r[a-zA-Z]*|-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*)\\s+/\\s*$"),
            Pattern.compile("rm\\s+(-[a-zA-Z]*f[a-zA-Z]*r[a-zA-Z]*|-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*)\\s+/\\*"),
            Pattern.compile("dd\\s+if=/dev/(zero|random|urandom)\\s+of=/dev/(s|h|vd|nvm)[a-z0-9]+\\b"),
            Pattern.compile("rm\\s+.*/etc/passwd"),
            Pattern.compile("rm\\s+.*/etc/shadow"),
            Pattern.compile("mkfs(?:\\.[a-z0-9]+)?\\s+/dev/"),
            Pattern.compile("chmod\\s+(-R\\s+)?777\\s+(/|/etc|/usr|/bin|/lib)\\s*$")
    );

    // ══════════════════════════════════════════════════════════════════
    // 2. 命令注入 & 恶意内容特征（FORBIDDEN）
    // ══════════════════════════════════════════════════════════════════

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("[;&]\\s*rm\\s+-[rf]"),
            Pattern.compile("[;&]\\s*dd\\s+if="),
            Pattern.compile("[;&]\\s*mkfs"),
            Pattern.compile("bash\\s+-[iI]\\s+>&?\\s*/dev/tcp"),
            Pattern.compile("\\bnc\\b.*-[el].*\\b(sh|bash)\\b"),
            Pattern.compile("python[23]?\\s+-c.*socket.*connect"),
            Pattern.compile("/dev/tcp/\\d+\\.\\d+\\.\\d+\\.\\d+"),
            Pattern.compile("\\b(xmrig|minerd|cryptonight|stratum\\+tcp)\\b"),
            Pattern.compile("base64\\s+-d.*\\|\\s*(sh|bash)"),
            Pattern.compile("curl.*\\|\\s*(sh|bash)"),
            Pattern.compile("wget.*-O.*\\|\\s*(sh|bash)")
    );

    // ══════════════════════════════════════════════════════════════════
    // 3. 路径分类
    // ══════════════════════════════════════════════════════════════════

    private static final Set<String> SAFE_PATH_PREFIXES = Set.of(
            "/tmp/", "/home/", "/var/tmp/", "/run/user/"
    );

    private static final Set<String> SYSTEM_PATH_PREFIXES = Set.of(
            "/etc/", "/usr/", "/bin/", "/sbin/", "/lib/", "/lib64/",
            "/boot/", "/sys/", "/proc/", "/root/"
    );

    private static final Set<String> MEDIUM_PATH_PREFIXES = Set.of(
            "/opt/", "/srv/", "/var/log/", "/var/run/", "/var/cache/"
    );

    private static final List<String> CRITICAL_SYSTEM_FILES = List.of(
            "/etc/passwd", "/etc/shadow", "/etc/sudoers",
            "/etc/ssh/sshd_config", "/boot/", "/etc/cron",
            "/etc/profile", "/etc/environment"
    );

    private static final Set<String> SENSITIVE_DOT_FILES = Set.of(
            ".bashrc", ".bash_profile", ".profile", ".zshrc",
            ".ssh/authorized_keys", ".ssh/config", ".gitconfig", ".env"
    );

    // ══════════════════════════════════════════════════════════════════
    // 4. 只读命令白名单（不含 echo，echo 可能携带重定向）
    // ══════════════════════════════════════════════════════════════════

    private static final List<String> READ_ONLY_PREFIXES = List.of(
            "cat ", "ls", "df", "du ", "ps ", "top ", "htop",
            "free", "uname", "whoami", "uptime", "hostname",
            "date", "ss ", "netstat", "grep ", "find ", "which ",
            "type ", "head ", "tail ", "wc ", "stat ", "file ",
            "lsof", "lsblk", "lscpu", "lsmem", "lsmod",
            "ip addr", "ip route", "ip link", "ifconfig",
            "systemctl status", "systemctl list", "journalctl",
            "env", "printenv", "pwd", "id", "groups", "last ",
            "history", "alias", "crontab -l", "w "
    );

    // ══════════════════════════════════════════════════════════════════
    // 5. 写入目标路径提取
    // ══════════════════════════════════════════════════════════════════

    private static final Pattern WRITE_REDIRECT = Pattern.compile(
            "(?<![<>])[>]{1,2}\\s*([^\\s;&|><\"']+)"
    );

    private static final Pattern TEE_TARGET = Pattern.compile(
            "\\btee\\s+(?:-a\\s+)?([^\\s;&|]+)"
    );

    // ══════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════

    /** 第一层：绝对禁止规则 */
    public boolean isAbsoluteForbidden(String command) {
        if (command == null || command.isBlank()) return false;
        String norm = normalize(command);
        for (Pattern p : ABSOLUTE_FORBIDDEN) {
            if (p.matcher(norm).find()) {
                log.warn("触发绝对禁止规则: {}", norm);
                return true;
            }
        }
        return false;
    }

    /** 命令注入 / 恶意内容检测 */
    public boolean isCommandInjection(String command) {
        if (command == null || command.isBlank()) return false;
        String norm = normalize(command);
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(norm).find()) {
                log.warn("检测到命令注入/恶意内容特征: {}", norm);
                return true;
            }
        }
        return false;
    }

    /** 纯只读命令快速放行（修正：不含 echo） */
    public boolean isClearlyReadOnly(String command) {
        if (command == null || command.isBlank()) return true;
        // 含重定向必然不是只读
        if (command.contains(">") || command.contains(">>")) return false;
        String lower = command.trim().toLowerCase();
        for (String prefix : READ_ONLY_PREFIXES) {
            if (lower.startsWith(prefix) || lower.equals(prefix.trim())) return true;
        }
        return false;
    }

    /** 判断是否为写入操作 */
    public boolean isWriteOperation(String command) {
        if (command == null || command.isBlank()) return false;
        String lower = command.toLowerCase();
        if (lower.contains(">")) return true;
        String[] writeCmds = {
                "tee ", "cp ", "mv ", "rm ", "touch ", "mkdir ", "rmdir ",
                "chmod ", "chown ", "chgrp ", "install ", " ln ",
                "useradd", "usermod", "userdel", "groupadd", "passwd ",
                "systemctl enable", "systemctl disable", "systemctl mask",
                "crontab -e", "crontab -r",
                "apt install", "apt remove", "apt purge",
                "yum install", "yum remove", "dnf install", "dnf remove",
                "pip install", "pip uninstall", "sed -i"
        };
        for (String w : writeCmds) {
            if (lower.contains(w)) return true;
        }
        return false;
    }

    /**
     * 基于路径 + 内容的写操作分级评估。
     * 返回 null 表示规则无法确定，需交 LLM 评估。
     */
    public RiskAssessment assessByPathAndContent(String command) {
        boolean hasSudo = command.matches(".*\\bsudo\\b.*");
        String target = extractWriteTarget(command);

        if (target == null) {
            // 无明确写入目标
            if (hasSudo) {
                return RiskAssessment.warning(command,
                        "命令使用了 sudo 权限，请确认操作目标和影响范围。");
            }
            return null; // 交 LLM 判断
        }

        // 关键系统文件 → FORBIDDEN
        for (String f : CRITICAL_SYSTEM_FILES) {
            if (target.startsWith(f) || target.equals(f)) {
                return RiskAssessment.forbidden(command,
                        "尝试写入关键系统文件「" + target + "」，此操作可能导致系统无法启动或认证失效，已永久拦截。");
            }
        }

        // 系统核心路径 → CRITICAL（sudo）/ WARNING（无 sudo）
        if (matchesAny(target, SYSTEM_PATH_PREFIXES)) {
            if (hasSudo) {
                return RiskAssessment.critical(command,
                        "使用 sudo 写入系统核心目录「" + target + "」，此操作将影响系统配置，请确认后执行。");
            }
            return RiskAssessment.warning(command,
                    "写入系统目录「" + target + "」，请确认操作意图及影响范围。");
        }

        // 中间路径 → CRITICAL（sudo）/ WARNING（无 sudo）
        if (matchesAny(target, MEDIUM_PATH_PREFIXES)) {
            if (hasSudo) {
                return RiskAssessment.critical(command,
                        "使用 sudo 写入「" + target + "」，请确认后执行。");
            }
            return RiskAssessment.warning(command,
                    "写入「" + target + "」，请确认操作合法性。");
        }

        // 用户安全路径 → SAFE（无 sudo）
        if (matchesAny(target, SAFE_PATH_PREFIXES)) {
            if (hasSudo) {
                return RiskAssessment.warning(command, "在用户目录使用了 sudo，请确认是否需要提权。");
            }
            return RiskAssessment.safe(command);
        }

        // 家目录 ~/
        if (target.startsWith("~/")) {
            String sub = target.substring(2);
            if (isSensitiveDotFile(sub)) {
                return RiskAssessment.warning(command,
                        "修改用户配置文件「" + target + "」，将影响 shell/SSH 环境，请确认修改内容。");
            }
            if (!hasSudo) return RiskAssessment.safe(command);
        }

        // 相对路径（视为当前用户目录）
        if (!target.startsWith("/")) {
            if (isSensitiveDotFile(target)) {
                return RiskAssessment.warning(command,
                        "写入用户配置文件「" + target + "」，该文件影响 shell 环境。");
            }
            if (!hasSudo) return RiskAssessment.safe(command);
        }

        return null; // 路径分类不明确，交 LLM
    }

    /** 生成绝对禁止指令的拒绝说明 */
    public String generateForbiddenRationale(String command) {
        String norm = command.trim();
        if (norm.contains(":(){ :|:")) {
            return "检测到 Fork Bomb 攻击指令。通过递归无限 fork 子进程耗尽系统资源，服务器将完全失去响应，已永久拦截。";
        }
        if (norm.matches(".*rm.*-.*rf.*/.*")) {
            return "检测到对根目录的强制递归删除操作。该操作将删除所有系统文件，服务器永久不可用，已永久拦截。";
        }
        if (norm.contains("dd") && norm.contains("/dev/")) {
            return "检测到 dd 直接向物理磁盘写入操作。将覆盖磁盘所有数据，数据永久丢失，已永久拦截。";
        }
        if (norm.contains("mkfs")) {
            return "检测到格式化磁盘操作（mkfs）。格式化将清除所有数据，不可恢复，已永久拦截。";
        }
        return "该指令被识别为可能导致服务器不可用的极端危险操作，已永久拦截。";
    }

    /** 解析 LLM 返回的 JSON 风险评估 */
    public RiskAssessment parseFromLlmJson(String command, String llmJson) {
        try {
            String level     = extractJsonField(llmJson, "level");
            String rationale = extractJsonField(llmJson, "rationale");
            String suggested = extractJsonField(llmJson, "suggestedAlternative");
            RiskLevel riskLevel = RiskLevel.valueOf(level.toUpperCase());
            return RiskAssessment.builder()
                    .level(riskLevel).command(command)
                    .rationale(rationale).suggestedAlternative(suggested)
                    .build();
        } catch (Exception e) {
            log.warn("LLM 安全评估解析失败，默认 WARNING: {}", llmJson);
            return RiskAssessment.warning(command, "安全评估结果解析异常，建议谨慎确认后执行。");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Private helpers
    // ══════════════════════════════════════════════════════════════════

    /** 针对 touch/mkdir/cp/mv 等不用重定向的写入命令的路径提取 */
    private static final Pattern TOUCH_MKDIR   = Pattern.compile(
            "^(?:touch|mkdir(?:\\s+-[pvm]+)*)\\s+(?:.*\\s+)?([^\\s;|&]+)$");
    private static final Pattern CP_MV_INSTALL = Pattern.compile(
            "^(?:cp|mv|install)(?:\\s+-[a-zA-Z]+)*\\s+\\S+\\s+([^\\s;|&]+)$");
    private static final Pattern CHOWN_CHMOD   = Pattern.compile(
            "^(?:chown|chmod)(?:\\s+-[a-zA-Z]+)*\\s+\\S+\\s+([^\\s;|&]+)$");

    private String extractWriteTarget(String command) {
        // 1. > / >> redirect (highest confidence)
        Matcher m = WRITE_REDIRECT.matcher(command);
        if (m.find()) return m.group(1).trim();
        // 2. tee command
        Matcher t = TEE_TARGET.matcher(command);
        if (t.find()) return t.group(1).trim();
        // 3. touch / mkdir → last argument
        String trim = command.trim();
        Matcher tm = TOUCH_MKDIR.matcher(trim);
        if (tm.find()) return tm.group(1).trim();
        // 4. cp / mv → destination (last arg)
        Matcher cm = CP_MV_INSTALL.matcher(trim);
        if (cm.find()) return cm.group(1).trim();
        // 5. chown / chmod → path argument
        Matcher cc = CHOWN_CHMOD.matcher(trim);
        if (cc.find()) return cc.group(1).trim();
        return null;
    }

    private boolean matchesAny(String path, Set<String> prefixes) {
        for (String p : prefixes) {
            if (path.startsWith(p)) return true;
        }
        return false;
    }

    private boolean isSensitiveDotFile(String path) {
        for (String f : SENSITIVE_DOT_FILES) {
            if (path.equals(f) || path.endsWith("/" + f)) return true;
        }
        return false;
    }

    private String normalize(String command) {
        return command.trim().replaceAll("\\s+", " ");
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
