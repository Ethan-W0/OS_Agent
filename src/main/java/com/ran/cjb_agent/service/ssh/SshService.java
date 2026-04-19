package com.ran.cjb_agent.service.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.ran.cjb_agent.service.security.SudoPasswordManager;
import com.ran.cjb_agent.websocket.StreamingResponseEmitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;

/**
 * SSH 命令执行服务（基于 JSch）
 * 封装 ChannelExec 的生命周期：创建、执行、收集输出、关闭
 */
@Slf4j
@Service
public class SshService {

    private final SshConnectionPool connectionPool;
    private final SshSessionContext sessionContext;
    private final SudoPasswordManager sudoPasswordManager;
    private final StreamingResponseEmitter emitter;

    @Value("${agent.ssh.command-timeout:60}")
    private int defaultCommandTimeoutSeconds;

    // @Lazy on emitter to break the potential circular dependency chain:
    // SshService → StreamingResponseEmitter → ChatHistoryService → ...
    public SshService(SshConnectionPool connectionPool,
                      SshSessionContext sessionContext,
                      SudoPasswordManager sudoPasswordManager,
                      @Lazy StreamingResponseEmitter emitter) {
        this.connectionPool = connectionPool;
        this.sessionContext = sessionContext;
        this.sudoPasswordManager = sudoPasswordManager;
        this.emitter = emitter;
    }

    /**
     * 在指定 SSH 连接上执行命令，返回标准输出（含 stderr）
     *
     * @param connectionId SSH 连接 ID
     * @param command      要执行的 Shell 命令
     * @param timeoutSec   超时时间（秒），0 表示使用默认值
     * @return 命令执行结果字符串
     */
    public String execute(String connectionId, String command, int timeoutSec) {
        int timeout = timeoutSec > 0 ? timeoutSec : defaultCommandTimeoutSeconds;
        log.debug("SSH执行 [{}]: {}", connectionId, command);

        Session session = connectionPool.getSession(connectionId);
        ChannelExec channel = null;

        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            channel.setOutputStream(stdout);
            channel.setErrStream(stderr);

            channel.connect(timeout * 1000);

            // 等待命令执行完成（带超时）
            long deadline = System.currentTimeMillis() + (long) timeout * 1000;
            while (!channel.isClosed()) {
                if (System.currentTimeMillis() > deadline) {
                    log.warn("SSH命令执行超时 [{}]: {}", connectionId, command);
                    return "⚠️ 命令执行超时（>" + timeout + "秒），请检查命令是否需要交互输入。";
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "⚠️ 命令执行被中断。";
                }
            }

            int exitCode = channel.getExitStatus();
            String out = stdout.toString("UTF-8").trim();
            String err = stderr.toString("UTF-8").trim();

            log.debug("SSH执行完成 [{}] exitCode={}", connectionId, exitCode);

            if (exitCode != 0 && !err.isEmpty()) {
                return buildErrorResult(out, err, exitCode);
            }
            return out.isEmpty() ? "（命令执行成功，无输出）" : out;

        } catch (JSchException | UnsupportedEncodingException e) {
            log.error("SSH命令执行异常 [{}]: {}", connectionId, command, e);
            throw new RuntimeException("SSH 命令执行失败: " + e.getMessage(), e);
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        }
    }

    /**
     * 使用 sudo 执行需要提权的命令。
     *
     * 决策顺序：
     * 1. SSH 用户已是 root → 直接执行，无需 sudo
     * 2. 有存储的 SSH 密码 → 尝试 echo password | sudo -S
     * 3. 尝试失败（无 TTY / 密码错误）→ 通过 WebSocket 向用户请求 sudo 密码
     * 4. 用户提交密码 → 用该密码执行 sudo -S
     *
     * @param connectionId SSH 连接 ID
     * @param command      不含 sudo 前缀的原始命令
     * @param timeoutSec   超时时间（秒）
     * @return 命令执行结果
     */
    public String executeWithSudo(String connectionId, String command, int timeoutSec) {
        var info = connectionPool.getConnectionInfo(connectionId);
        if (info == null) {
            return "⚠️ 无法获取连接信息，sudo 执行失败。";
        }

        // 1. SSH 用户已是 root，直接执行
        if ("root".equals(info.getUsername())) {
            log.debug("SSH root直接执行 [{}]: {}", connectionId, command);
            return execute(connectionId, command, timeoutSec);
        }

        // 2. 尝试用存储的 SSH 密码执行 sudo -S
        String storedPassword = info.getPassword();
        if (storedPassword != null && !storedPassword.isBlank()) {
            String result = executeSudoWithPassword(connectionId, command, storedPassword, timeoutSec);
            if (!isSudoAuthFailure(result)) {
                return result;
            }
            log.info("存储密码 sudo 认证失败，转为请求用户输入 [{}]", connectionId);
        }

        // 3. 无密码或密码错误 → 请求用户通过聊天框输入 sudo 密码
        return requestSudoPasswordFromUser(connectionId, command, timeoutSec);
    }

    /**
     * 使用指定密码通过 sudo -S 执行命令
     */
    private String executeSudoWithPassword(String connectionId, String command, String password, int timeoutSec) {
        String escapedPwd = password.replace("'", "'\\''");
        String sudoCmd = "echo '" + escapedPwd + "' | sudo -S -p '' " + command;
        log.debug("SSH sudo(-S)执行 [{}]: {}", connectionId, command);
        return execute(connectionId, sudoCmd, timeoutSec);
    }

    /**
     * 判断命令结果是否为 sudo 认证失败（需要密码输入但无 TTY）
     */
    private boolean isSudoAuthFailure(String result) {
        if (result == null) return false;
        return result.contains("a terminal is required")
                || result.contains("no tty present")
                || result.contains("sudo: 需要密码")
                || result.contains("incorrect password")
                || result.contains("3 incorrect password")
                || (result.contains("sudo") && result.contains("password"));
    }

    /**
     * 向前端推送 SUDO_REQUEST，挂起等待用户输入 sudo 密码后重试命令
     */
    private String requestSudoPasswordFromUser(String connectionId, String command, int timeoutSec) {
        String sessionId = sessionContext.getSessionId(connectionId);
        if (sessionId == null) {
            log.warn("无法获取 sessionId，sudo 请求无法推送到前端 [conn={}]", connectionId);
            return "⚠️ 需要 sudo 权限，但无法确定当前会话。请确认 SSH 用户有 sudo 权限后重试。";
        }

        log.info("向用户请求 sudo 密码 [session={}, conn={}]", sessionId, connectionId);
        emitter.pushSudoRequest(sessionId,
                "🔐 命令需要 **sudo** 权限，请在下方输入 sudo 密码以继续执行：\n```\n" + command + "\n```");

        // 挂起当前线程，等待用户通过 POST /api/security/sudo-password 提交密码
        String password = sudoPasswordManager.waitForPassword(sessionId);

        if (password == null || password.isBlank()) {
            return "⚠️ sudo 密码未提供或等待超时，操作已取消。";
        }

        // 用用户输入的密码重试
        log.info("已收到 sudo 密码，重试执行 [session={}]", sessionId);
        String result = executeSudoWithPassword(connectionId, command, password, timeoutSec);

        if (isSudoAuthFailure(result)) {
            return "❌ sudo 密码错误，执行失败。请确认密码后重试。\n详细信息：" + result;
        }
        return result;
    }

    /**
     * 执行命令并返回是否成功（exitCode == 0）
     */
    public boolean executeCheck(String connectionId, String command) {
        String result = execute(connectionId, command, 10);
        return !result.startsWith("⚠️");
    }

    /**
     * 快速测试 SSH 连接是否可用
     */
    public String testConnection(String connectionId) {
        try {
            return execute(connectionId, "echo 'SSH连接测试成功' && uname -a", 5);
        } catch (Exception e) {
            return "连接测试失败: " + e.getMessage();
        }
    }

    private String buildErrorResult(String stdout, String stderr, int exitCode) {
        StringBuilder sb = new StringBuilder();
        if (!stdout.isEmpty()) {
            sb.append(stdout).append("\n");
        }
        sb.append("⚠️ 命令执行返回错误（退出码: ").append(exitCode).append("）\n");
        sb.append("错误信息: ").append(stderr);
        return sb.toString();
    }
}
