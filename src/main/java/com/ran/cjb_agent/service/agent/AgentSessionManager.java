package com.ran.cjb_agent.service.agent;

import com.ran.cjb_agent.model.domain.AgentSession;
import com.ran.cjb_agent.model.enums.AgentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 会话生命周期管理器
 * 负责：Session 创建/读取/更新/删除、心跳检测、过期清理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSessionManager {

    private static final long SESSION_EXPIRE_MINUTES = 60; // 60 分钟无活动则过期

    private final ConcurrentHashMap<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final ConversationMemoryStore memoryStore;

    /**
     * 获取或创建 Session
     */
    public AgentSession getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            // sessionId 为空时创建匿名 Session，并手动存入 Map
            AgentSession session = createNew(null);
            sessions.put(session.getSessionId(), session);
            return session;
        }
        // computeIfAbsent 会自动将 createNew 的返回值存入 Map，
        // 因此 createNew 内部不能再调用 sessions.put()，否则触发 Recursive update
        return sessions.computeIfAbsent(sessionId, this::createNew);
    }

    /**
     * 创建新 Session
     */
    public AgentSession createNew(String requestedId) {
        String id = (requestedId != null && !requestedId.isBlank())
                ? requestedId : UUID.randomUUID().toString();

        AgentSession session = AgentSession.builder()
                .sessionId(id)
                .status(AgentStatus.IDLE)
                .lastActiveAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        // 注意：当通过 computeIfAbsent 调用时，不能在此处再调用 sessions.put()，
        // 否则会触发 ConcurrentHashMap 的 Recursive update 异常。
        // computeIfAbsent 会自动将返回值写入 Map；
        // 直接调用 createNew(null) 的场景（sessionId 为空）由调用方自行处理。
        log.info("新 Session 已创建: {}", id);
        return session;
    }

    /**
     * 更新 Session 状态并刷新心跳时间
     */
    public void updateStatus(String sessionId, AgentStatus status) {
        AgentSession session = sessions.get(sessionId);
        if (session != null) {
            session.setStatus(status);
            session.setLastActiveAt(Instant.now());
        }
    }

    /**
     * 绑定 SSH 连接
     */
    public void bindSshConnection(String sessionId, String sshConnectionId) {
        AgentSession session = sessions.get(sessionId);
        if (session != null) {
            session.setBoundSshConnectionId(sshConnectionId);
            log.debug("Session [{}] 绑定 SSH 连接: {}", sessionId, sshConnectionId);
        }
    }

    /**
     * 获取 Session（不创建）
     */
    public AgentSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 删除 Session（清空记忆）
     */
    public void remove(String sessionId) {
        sessions.remove(sessionId);
        memoryStore.clear(sessionId);
        log.info("Session 已删除: {}", sessionId);
    }

    /**
     * 定时清理过期 Session（每 10 分钟执行一次）
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void cleanupExpiredSessions() {
        Instant expireThreshold = Instant.now().minus(SESSION_EXPIRE_MINUTES, ChronoUnit.MINUTES);
        sessions.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().getLastActiveAt().isBefore(expireThreshold);
            if (expired) {
                memoryStore.clear(entry.getKey());
                log.info("过期 Session 已清理: {}", entry.getKey());
            }
            return expired;
        });
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }
}
