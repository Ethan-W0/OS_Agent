package com.ran.cjb_agent.service.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;

/**
 * SSH 命令执行服务（基于 JSch）
 * 封装 ChannelExec 的生命周期：创建、执行、收集输出、关闭
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SshService {

    private final SshConnectionPool connectionPool;

    @Value("${agent.ssh.command-timeout:60}")
    private int defaultCommandTimeoutSeconds;

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
