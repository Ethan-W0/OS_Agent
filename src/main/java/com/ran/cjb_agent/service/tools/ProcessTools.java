package com.ran.cjb_agent.service.tools;

import com.ran.cjb_agent.service.os.OsProfileCache;
import com.ran.cjb_agent.service.ssh.SshService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 进程工具集（LangChain4j @Tool）
 * 覆盖：进程查询、资源占用分析、进程终止
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessTools {

    private final SshService sshService;
    private final OsProfileCache osProfileCache;

    @Tool("列出服务器当前运行的进程，按 CPU 占用从高到低排序，显示 Top 20")
    public String listProcessesByCpu(
            @P("SSH连接ID") String sshConnectionId) {

        String cmd = osProfileCache.get(sshConnectionId)
                .map(p -> p.getCommandMap().getOrDefault("processView", "ps aux --sort=-%cpu | head -20"))
                .orElse("ps aux --sort=-%cpu | head -20");

        String result = sshService.execute(sshConnectionId, cmd, 15);
        return "【进程列表（按CPU占用排序）】\n" + result;
    }

    @Tool("按内存占用从高到低列出进程 Top 20")
    public String listProcessesByMemory(
            @P("SSH连接ID") String sshConnectionId) {

        String result = sshService.execute(sshConnectionId, "ps aux --sort=-%mem | head -20", 15);
        return "【进程列表（按内存占用排序）】\n" + result;
    }

    @Tool("按进程名或关键字搜索特定进程，返回匹配的进程信息和 PID")
    public String findProcess(
            @P("SSH连接ID") String sshConnectionId,
            @P("进程名称或关键字，例如 nginx、java、python") String processName) {

        String cmd = "ps aux | grep " + processName + " | grep -v grep";
        String result = sshService.execute(sshConnectionId, cmd, 10);
        if (result.isBlank() || result.contains("命令执行成功，无输出")) {
            return "未找到名称包含 '" + processName + "' 的进程。";
        }
        return "【进程搜索结果：" + processName + "】\n" + result;
    }

    @Tool("查询系统整体 CPU 和负载情况，包括 1/5/15 分钟平均负载")
    public String checkSystemLoad(
            @P("SSH连接ID") String sshConnectionId) {

        String uptime   = sshService.execute(sshConnectionId, "uptime", 5);
        String cpuCount = sshService.execute(sshConnectionId, "nproc", 5);
        return String.format("【系统负载】\n%s\nCPU 核心数：%s 核", uptime.trim(), cpuCount.trim());
    }

    @Tool("终止指定 PID 的进程。注意：此操作不可撤销，请确认 PID 正确后再执行")
    public String killProcess(
            @P("SSH连接ID") String sshConnectionId,
            @P("要终止的进程 PID（必须是有效的进程 ID 数字）") int pid,
            @P("信号类型：TERM（优雅终止，默认）或 KILL（强制终止）") String signal) {

        // 安全校验：不允许 kill 关键系统进程
        if (pid <= 1) {
            return "❌ 拒绝操作：PID " + pid + " 是系统关键进程，禁止终止。";
        }

        String sig = ("KILL".equalsIgnoreCase(signal)) ? "-9" : "-15";
        String cmd = "kill " + sig + " " + pid;

        // 先查询进程名（确认操作对象）
        String procInfo = sshService.execute(sshConnectionId,
                "ps -p " + pid + " -o comm= 2>/dev/null || echo '进程不存在'", 5);

        if (procInfo.contains("进程不存在")) {
            return "PID " + pid + " 对应的进程不存在或已结束。";
        }

        String result = sshService.execute(sshConnectionId, cmd, 10);
        return String.format("✅ 已向进程 PID=%d（%s）发送 %s 信号。\n%s",
                pid, procInfo.trim(), "KILL".equalsIgnoreCase(signal) ? "SIGKILL（强制终止）" : "SIGTERM（优雅终止）", result);
    }
    @Tool("查询系统服务（Systemd/SysVinit）的运行状态，包括服务是否激活、是否开机自启、最近日志等。" +
            "适用于：查看 nginx/mysql/redis/sshd 等服务状态，比 ps 进程搜索更准确全面。")
    public String getServiceStatus(
            @P("SSH连接ID") String sshConnectionId,
            @P("服务名称，例如 nginx、mysql、sshd、docker") String serviceName) {

        if (serviceName == null || serviceName.isBlank()) {
            return "请提供服务名称。";
        }

        // 优先 systemctl（systemd），降级到 service（SysVinit）
        String cmd = String.format(
                "systemctl status %s 2>/dev/null || service %s status 2>/dev/null || " +
                        "echo '未找到服务 %s，请确认服务名是否正确'",
                serviceName, serviceName, serviceName
        );

        String result = sshService.execute(sshConnectionId, cmd, 15);
        return String.format("【服务状态：%s】\n%s", serviceName, result);
    }

}
