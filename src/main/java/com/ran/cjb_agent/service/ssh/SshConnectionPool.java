package com.ran.cjb_agent.service.ssh;

import com.jcraft.jsch.*;
import com.ran.cjb_agent.model.domain.SshConnectionInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH 连接池：管理 JSch Session 的创建、复用、健康检查与断线重连
 */
@Slf4j
@Component
public class SshConnectionPool {

    @Value("${agent.ssh.connect-timeout:10000}")
    private int connectTimeout;

    @Value("${agent.ssh.heartbeat-interval:30000}")
    private int heartbeatInterval;

    /**
     * connectionId → JSch Session（活跃连接池）
     */
    private final Map<String, Session> sessionPool = new ConcurrentHashMap<>();

    /**
     * connectionId → 连接配置（用于断线重连）
     */
    private final Map<String, SshConnectionInfo> connectionInfoMap = new ConcurrentHashMap<>();

    /**
     * 注册并建立 SSH 连接
     */
    public Session connect(SshConnectionInfo info) throws JSchException {
        log.info("建立 SSH 连接: {}@{}:{}", info.getUsername(), info.getHost(), info.getPort());

        JSch jsch = new JSch();

        // 私钥认证
        if (info.getPrivateKey() != null && !info.getPrivateKey().isEmpty()) {
            byte[] privateKeyBytes = info.getPrivateKey().getBytes();
            jsch.addIdentity(info.getId(), privateKeyBytes, null, null);
        }

        Session session = jsch.getSession(info.getUsername(), info.getHost(), info.getPort());

        // 密码认证
        if (info.getPassword() != null && !info.getPassword().isEmpty()) {
            session.setPassword(info.getPassword());
        }

        // JSch 配置
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no"); // 开发/演示阶段关闭主机验证
        config.put("PreferredAuthentications", "publickey,keyboard-interactive,password");
        session.setConfig(config);

        // 服务器保活（每 30 秒发送 keepalive）
        session.setServerAliveInterval(heartbeatInterval);
        session.setServerAliveCountMax(3);

        session.connect(connectTimeout);

        sessionPool.put(info.getId(), session);
        connectionInfoMap.put(info.getId(), info);

        log.info("SSH 连接已建立: {}", info.getId());
        return session;
    }

    /**
     * 获取 Session（自动检查连通性，断线则重连）
     */
    public Session getSession(String connectionId) {
        Session session = sessionPool.get(connectionId);

        if (session == null) {
            throw new IllegalStateException("SSH 连接不存在: " + connectionId + "，请先添加并测试连接。");
        }

        if (!session.isConnected()) {
            log.warn("SSH 连接已断线，尝试重连: {}", connectionId);
            session = reconnect(connectionId);
        }

        return session;
    }

    /**
     * 断线重连
     */
    private Session reconnect(String connectionId) {
        SshConnectionInfo info = connectionInfoMap.get(connectionId);
        if (info == null) {
            throw new IllegalStateException("无法重连，连接配置不存在: " + connectionId);
        }
        try {
            return connect(info);
        } catch (JSchException e) {
            log.error("SSH 重连失败: {}", connectionId, e);
            throw new RuntimeException("SSH 重连失败: " + e.getMessage(), e);
        }
    }

    /**
     * 断开并移除连接
     */
    public void disconnect(String connectionId) {
        Session session = sessionPool.remove(connectionId);
        connectionInfoMap.remove(connectionId);
        if (session != null && session.isConnected()) {
            session.disconnect();
            log.info("SSH 连接已断开: {}", connectionId);
        }
    }

    /**
     * 检查连接是否活跃
     */
    public boolean isConnected(String connectionId) {
        Session session = sessionPool.get(connectionId);
        return session != null && session.isConnected();
    }

    /**
     * 获取所有连接 ID
     */
    public java.util.Set<String> getActiveConnectionIds() {
        return sessionPool.keySet();
    }

    /**
     * 获取连接配置
     */
    public SshConnectionInfo getConnectionInfo(String connectionId) {
        return connectionInfoMap.get(connectionId);
    }

    /**
     * 获取所有连接配置（供前端展示）
     */
    public java.util.Collection<SshConnectionInfo> getAllConnectionInfos() {
        return connectionInfoMap.values();
    }
}
