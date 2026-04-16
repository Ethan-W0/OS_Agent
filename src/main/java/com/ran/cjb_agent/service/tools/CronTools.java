package com.ran.cjb_agent.service.tools;

import com.ran.cjb_agent.service.ssh.SshService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 定时任务工具集（LangChain4j @Tool）
 * 覆盖：crontab 查询、添加、删除定时任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CronTools {

    private final SshService sshService;

    @Tool("查看当前用户或指定用户的 crontab 定时任务列表")
    public String listCronJobs(
            @P("SSH连接ID") String sshConnectionId,
            @P("要查看的用户名，留空则查看当前连接用户的 crontab") String username) {

        String cmd = (username == null || username.isBlank())
                ? "crontab -l 2>/dev/null || echo '（当前用户没有定时任务）'"
                : "crontab -u " + username + " -l 2>/dev/null || echo '（用户 " + username + " 没有定时任务）'";

        String result = sshService.execute(sshConnectionId, cmd, 10);
        return "【Crontab 定时任务列表】\n" + result;
    }

    @Tool("查看系统级定时任务（/etc/cron.d/ 和 /etc/crontab），包含所有系统维护任务")
    public String listSystemCronJobs(
            @P("SSH连接ID") String sshConnectionId) {

        String cmd = "echo '=== /etc/crontab ===' && cat /etc/crontab 2>/dev/null && " +
                     "echo '=== /etc/cron.d/ ===' && ls -la /etc/cron.d/ 2>/dev/null && " +
                     "for f in /etc/cron.d/*; do echo \"--- $f ---\"; cat $f 2>/dev/null; done";

        String result = sshService.execute(sshConnectionId, cmd, 15);
        return "【系统定时任务】\n" + result;
    }

    @Tool("为指定用户添加一条 crontab 定时任务。Cron 表达式格式：分 时 日 月 周 命令")
    public String addCronJob(
            @P("SSH连接ID") String sshConnectionId,
            @P("Cron 表达式（前5字段），例如 '0 2 * * *' 表示每天凌晨2点，'*/5 * * * *' 表示每5分钟") String cronExpression,
            @P("要执行的命令，例如 /usr/bin/backup.sh >> /var/log/backup.log 2>&1") String command,
            @P("要操作的用户名，留空则使用当前连接用户") String username) {

        // 校验 cron 表达式格式（简单验证：5个字段）
        if (cronExpression == null || cronExpression.trim().split("\\s+").length != 5) {
            return "❌ Cron 表达式格式错误，需要5个字段（分 时 日 月 周），例如：'0 2 * * *'";
        }

        String newCronLine = cronExpression.trim() + " " + command.trim();

        // 读取现有 crontab → 追加新任务 → 写回（原子操作）
        String userFlag = (username == null || username.isBlank()) ? "" : " -u " + username;
        String cmd = String.format(
                "(crontab%s -l 2>/dev/null; echo '%s') | crontab%s -",
                userFlag, newCronLine, userFlag
        );

        String result = sshService.execute(sshConnectionId, cmd, 10);

        // 验证添加结果
        String verify = sshService.execute(sshConnectionId,
                "crontab" + userFlag + " -l 2>/dev/null | grep -F '" + command.trim() + "'", 5);

        if (!verify.isBlank() && !verify.contains("命令执行成功，无输出")) {
            return "✅ 定时任务添加成功！\n新任务：" + newCronLine + "\n验证：" + verify;
        }
        return "⚠️ 定时任务可能已添加，请通过「查看定时任务」确认：\n" + result;
    }

    @Tool("删除指定用户 crontab 中包含特定命令的定时任务行")
    public String removeCronJob(
            @P("SSH连接ID") String sshConnectionId,
            @P("要删除的任务关键字（匹配命令中的字符串）") String commandKeyword,
            @P("要操作的用户名，留空则使用当前连接用户") String username) {

        String userFlag = (username == null || username.isBlank()) ? "" : " -u " + username;

        // 先展示将被删除的行（让用户确认）
        String preview = sshService.execute(sshConnectionId,
                "crontab" + userFlag + " -l 2>/dev/null | grep -n '" + commandKeyword + "'", 5);

        if (preview.isBlank() || preview.contains("命令执行成功，无输出")) {
            return "未找到包含 '" + commandKeyword + "' 的定时任务，无需删除。";
        }

        // 删除匹配行
        String cmd = String.format(
                "crontab%s -l 2>/dev/null | grep -v '%s' | crontab%s -",
                userFlag, commandKeyword, userFlag
        );
        sshService.execute(sshConnectionId, cmd, 10);

        return "✅ 已删除包含 '" + commandKeyword + "' 的定时任务：\n" + preview;
    }
}
