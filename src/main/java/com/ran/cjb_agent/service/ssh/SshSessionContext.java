package com.ran.cjb_agent.service.ssh;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH 连接 ID → 会话 ID 的绑定上下文
 *
 * AgentOrchestrator 在启动工具调用前注册绑定，
 * SshService 读取此绑定以获得当前 sessionId（用于 SUDO_REQUEST 推送）。
 */
@Component
public class SshSessionContext {

    private final ConcurrentHashMap<String, String> connectionToSession = new ConcurrentHashMap<>();

    public void bind(String connectionId, String sessionId) {
        if (connectionId != null && sessionId != null) {
            connectionToSession.put(connectionId, sessionId);
        }
    }

    public String getSessionId(String connectionId) {
        return connectionToSession.get(connectionId);
    }

    public void unbind(String connectionId) {
        if (connectionId != null) {
            connectionToSession.remove(connectionId);
        }
    }
}
