package com.ran.cjb_agent.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chat_sessions")
public class ChatSessionEntity {

    @Id
    @Column(length = 64)
    private String sessionId;

    @Column(length = 64)
    private String boundSshConnectionId;

    @Column(length = 20)
    @Builder.Default
    private String status = "IDLE";

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(nullable = false)
    private Instant lastActiveAt = Instant.now();
}
