package com.ran.cjb_agent.service.persistence;

import com.ran.cjb_agent.model.dto.ChatResponse;
import com.ran.cjb_agent.model.entity.ChatMessageEntity;
import com.ran.cjb_agent.model.entity.ChatSessionEntity;
import com.ran.cjb_agent.model.enums.MessageType;
import com.ran.cjb_agent.repository.ChatMessageRepository;
import com.ran.cjb_agent.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private final ChatMessageRepository messageRepository;
    private final ChatSessionRepository sessionRepository;

    private final Map<String, StringBuilder> tokenBuffers = new ConcurrentHashMap<>();

    public void ensureSession(String sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            sessionRepository.save(ChatSessionEntity.builder()
                    .sessionId(sessionId)
                    .build());
        }
    }

    public void updateSessionActivity(String sessionId, String sshConnectionId) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setLastActiveAt(Instant.now());
            if (sshConnectionId != null) {
                session.setBoundSshConnectionId(sshConnectionId);
            }
            sessionRepository.save(session);
        });
    }

    public void saveUserMessage(String sessionId, String content) {
        ensureSession(sessionId);
        messageRepository.save(ChatMessageEntity.builder()
                .sessionId(sessionId)
                .type("USER")
                .content(content)
                .finished(true)
                .createdAt(Instant.now())
                .build());
    }

    public void saveAgentMessage(String sessionId, ChatResponse response) {
        MessageType type = response.getType();

        // TOKEN messages are accumulated, not saved individually
        if (type == MessageType.TOKEN) {
            handleToken(sessionId, response);
            return;
        }

        // NODE_PROGRESS is transient display info, skip persistence
        if (type == MessageType.NODE_PROGRESS) {
            return;
        }

        messageRepository.save(ChatMessageEntity.builder()
                .sessionId(sessionId)
                .type(type.name())
                .content(response.getContent())
                .command(response.getCommand())
                .riskLevel(response.getRiskLevel() != null ? response.getRiskLevel().name() : null)
                .rationale(response.getRationale())
                .suggestedAlternative(response.getSuggestedAlternative())
                .confirmationToken(response.getConfirmationToken())
                .nodeName(response.getNodeName())
                .finished(response.isFinished())
                .createdAt(Instant.now())
                .build());
    }

    private void handleToken(String sessionId, ChatResponse response) {
        String token = response.getContent();
        boolean finished = response.isFinished();

        if (token != null && !token.isEmpty()) {
            tokenBuffers.computeIfAbsent(sessionId, k -> new StringBuilder()).append(token);
        }

        if (finished) {
            StringBuilder buffer = tokenBuffers.remove(sessionId);
            String fullContent = buffer != null ? buffer.toString() : "";
            if (!fullContent.isEmpty()) {
                messageRepository.save(ChatMessageEntity.builder()
                        .sessionId(sessionId)
                        .type("TEXT")
                        .content(fullContent)
                        .finished(true)
                        .createdAt(Instant.now())
                        .build());
            }
        }
    }

    public List<ChatMessageEntity> getHistory(String sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @Transactional
    public void clearHistory(String sessionId) {
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
        tokenBuffers.remove(sessionId);
        log.info("已清除会话历史: {}", sessionId);
    }

    public void markConfirmation(String confirmationToken, boolean approved) {
        // No-op for now; confirmation state is tracked in-memory
    }

    public List<Map<String, Object>> listSessions() {
        return sessionRepository.findAllByOrderByLastActiveAtDesc().stream()
                .map(session -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("sessionId", session.getSessionId());
                    info.put("createdAt", session.getCreatedAt().toString());
                    info.put("lastActiveAt", session.getLastActiveAt().toString());
                    info.put("messageCount", messageRepository.countBySessionId(session.getSessionId()));
                    // First user message as preview
                    messageRepository.findFirstBySessionIdAndTypeOrderByCreatedAtAsc(session.getSessionId(), "USER")
                            .ifPresent(msg -> {
                                String preview = msg.getContent();
                                if (preview != null && preview.length() > 50) {
                                    preview = preview.substring(0, 50) + "...";
                                }
                                info.put("preview", preview);
                            });
                    return info;
                })
                .collect(Collectors.toList());
    }
}
