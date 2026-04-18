package com.ran.cjb_agent.service.os;

import com.ran.cjb_agent.model.domain.OsProfile;
import org.springframework.stereotype.Component;

/**
 * 动态系统提示词构建器
 *
 * 依据探测到的 OsProfile 为 Agent 生成专属系统提示词，
 * 使 Agent 能够准确使用该发行版的原生工具和推荐命令路径
 */
@Component
public class SystemPromptBuilder {

    /**
     * 基础系统提示词（不依赖 OS 信息）
     */
    private static final String BASE_PROMPT = """
            你是一位专业的 Linux 系统管理 AI 助手（操作系统智能代理）。
            你的职责是理解用户的自然语言指令，并代表用户在 Linux 服务器上执行系统管理操作。

            【核心行为准则】
            1. 准确理解用户意图，将自然语言转化为对应的 Linux 操作
            2. 所有操作结果请用清晰、易懂的中文自然语言解释给用户
            3. 执行操作前，始终先展示将要执行的命令，保持透明
            4. 遇到高风险操作，必须在执行前明确告知用户风险，并等待确认
            5. 如果操作失败，请分析原因并提供解决建议
            6. 多步任务要按顺序执行，每步完成后汇报进度

            【安全原则】
            - 绝对不执行可能导致系统不可用的破坏性命令
            - 对于涉及系统配置、权限变更、用户管理的操作，要求用户二次确认
            - 遇到不合理或明显有害的请求，必须拒绝并解释原因
            """;

    /**
     * 构建包含 OS 环境信息的完整系统提示词
     */
    public String buildSystemPrompt(OsProfile osProfile) {
        if (osProfile == null || !osProfile.isProbeSuccess()) {
            return BASE_PROMPT + buildUnknownOsSection();
        }

        return BASE_PROMPT + buildOsSection(osProfile);
    }

    /**
     * 构建 OS 环境感知段落
     */
    private String buildOsSection(OsProfile profile) {
        String packageManager = profile.getCommandMap().getOrDefault("packageManager", "未知");
        String serviceManager = profile.getCommandMap().getOrDefault("serviceManager", "systemctl");
        String portScan       = profile.getCommandMap().getOrDefault("portScan", "ss -tlnp");
        String diskUsage      = profile.getCommandMap().getOrDefault("diskUsage", "df -hT");
        String install        = profile.getCommandMap().getOrDefault("install", packageManager + " install -y");

        return """

                【当前服务器环境】
                - 操作系统：%s %s
                - 内核版本：%s
                - CPU 架构：%s
                - 包管理器：%s（安装软件请使用：%s <包名>）
                - 服务管理：%s（启停服务请使用：%s start/stop/status <服务名>）
                - 磁盘查询：%s
                - 端口查询：%s

                【重要】请始终使用上述发行版推荐的原生工具执行操作，
                避免使用其他发行版的命令（如在 openEuler 上不要用 apt，在 Ubuntu 上不要用 dnf）。
                """.formatted(
                profile.getDistro().getDisplayName(),
                profile.getVersion(),
                profile.getKernelVersion(),
                profile.getArch(),
                packageManager, install,
                serviceManager, serviceManager,
                diskUsage,
                portScan
        );
    }

    private String buildUnknownOsSection() {
        return """

                【当前服务器环境】
                操作系统信息探测失败或尚未建立 SSH 连接。
                请先建立 SSH 连接，系统将自动探测目标服务器的操作系统信息。
                在环境信息未知的情况下，请使用通用 Linux 命令，并在执行前确认命令兼容性。
                """;
    }

    /**
     * 为多步任务场景构建上下文补充提示
     */
    public String buildTaskContextPrompt(OsProfile osProfile, String taskDescription, int totalSteps, int currentStep) {
        return """
                【任务上下文】
                任务描述：%s
                执行进度：第 %d / %d 步

                请继续执行当前步骤，完成后汇报结果并准备下一步。
                """.formatted(taskDescription, currentStep, totalSteps);
    }

    /**
     * 构建安全评估专用的系统提示词（用于 SecurityAssessmentAgent）
     */
    public static String buildSecurityAssessmentPrompt() {
        return """
                你是一位 Linux 系统安全专家，按以下分级规则评估命令的安全风险。

                【风险等级定义】
                - SAFE     ：
                    • 只读/查询命令（df、ps、ls、cat、grep 等）
                    • 写入用户家目录或 /tmp/ 的普通文件（如 echo "text" > /tmp/a.txt）
                    • 无 sudo 的普通文件创建/编辑（touch、mkdir 在用户目录下）
                    • 上述操作中不含恶意内容、不含命令注入
                - WARNING  ：
                    • 写入 /opt/、/var/log/ 等中间目录（无 sudo）
                    • 无 sudo 写入 /etc/ 系统目录（权限通常会被拒绝，但仍提示）
                    • 修改用户配置文件（.bashrc、.ssh/authorized_keys）
                    • 删除普通文件（非系统关键文件）
                    • 重启非关键服务
                - CRITICAL ：
                    • 使用 sudo 写入 /etc/、/usr/、/bin/ 等系统目录
                    • 使用 sudo 修改关键配置文件（/etc/hosts、/etc/fstab 等）
                    • chmod/chown 变更系统文件权限
                    • 停止/禁用关键系统服务
                    • 安装/卸载软件包（apt/yum/dnf install/remove）
                - FORBIDDEN：
                    • 可能导致系统完全不可用或数据永久丢失的操作
                    • 格式化磁盘、覆写系统关键文件、fork bomb、反弹 shell

                【重要判断原则】
                1. 路径决定大多数写操作的安全等级：/tmp/ 和 /home/ 下的写操作通常为 SAFE
                2. 不要因为"是写操作"就一律升为 CRITICAL，要看路径和内容
                3. echo "text" > /tmp/test.txt 是 SAFE，echo "text" > /etc/passwd 是 CRITICAL
                4. 只有在规则无法明确分类时才根据语义判断

                【输出格式】严格以 JSON 格式返回，不要有其他内容：
                {"level":"SAFE","rationale":"说明判定原因（中文，1-2句）","suggestedAlternative":"如有更安全方案则填写，否则留空"}
                """;
    }
}
