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
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_session_id", columnList = "sessionId"),
        @Index(name = "idx_created_at", columnList = "createdAt")
})
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(length = 1000)
    private String command;

    @Column(length = 20)
    private String riskLevel;

    @Column(columnDefinition = "TEXT")
    private String rationale;

    @Column(length = 500)
    private String suggestedAlternative;

    @Column(length = 64)
    private String confirmationToken;

    @Column(length = 100)
    private String nodeName;

    @Builder.Default
    private Boolean finished = true;

    private Boolean confirmed;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
