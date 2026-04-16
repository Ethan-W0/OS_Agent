package com.ran.cjb_agent.model.domain;

import com.ran.cjb_agent.model.enums.AgentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Agent 会话：代表一个用户与 Agent 的完整对话生命周期
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSession {

    /**
     * 会话唯一 ID
     */
    private String sessionId;

    /**
     * 当前绑定的 SSH 连接 ID
     */
    private String boundSshConnectionId;

    /**
     * 当前连接服务器的 OS Profile
     */
    private OsProfile osProfile;

    /**
     * Agent 当前状态
     */
    @Builder.Default
    private AgentStatus status = AgentStatus.IDLE;

    /**
     * 最后活跃时间（用于心跳检测）
     */
    @Builder.Default
    private Instant lastActiveAt = Instant.now();

    /**
     * 当前挂起等待确认的操作（若有）
     */
    private PendingConfirmation pendingConfirmation;

    /**
     * 会话创建时间
     */
    @Builder.Default
    private Instant createdAt = Instant.now();
}
