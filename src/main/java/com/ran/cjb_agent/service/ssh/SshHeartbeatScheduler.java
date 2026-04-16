package com.ran.cjb_agent.service.ssh;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * SSH 心跳保活调度器
 * 定期向所有活跃 SSH 连接发送 keepalive 命令，防止会话超时断开
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SshHeartbeatScheduler {

    private final SshConnectionPool connectionPool;
    private final SshService sshService;

    /**
     * 每 25 秒发送一次心跳（比 JSch serverAliveInterval=30s 短，确保保活有效）
     */
    @Scheduled(fixedDelay = 25000, initialDelay = 30000)
    public void sendHeartbeat() {
        Set<String> activeIds = connectionPool.getActiveConnectionIds();
        if (activeIds.isEmpty()) {
            return;
        }

        log.debug("发送 SSH 心跳，活跃连接数: {}", activeIds.size());

        for (String connectionId : activeIds) {
            if (connectionPool.isConnected(connectionId)) {
                try {
                    // 发送轻量心跳命令
                    sshService.execute(connectionId, "echo heartbeat", 5);
                    log.debug("SSH 心跳成功: {}", connectionId);
                } catch (Exception e) {
                    log.warn("SSH 心跳失败 [{}]: {}", connectionId, e.getMessage());
                    // 心跳失败不主动断开，下次 getSession 时会自动重连
                }
            }
        }
    }
}
