package com.ran.cjb_agent.controller;

import com.ran.cjb_agent.model.dto.ChatRequest;
import com.ran.cjb_agent.model.entity.ChatMessageEntity;
import com.ran.cjb_agent.service.agent.AgentOrchestrator;
import com.ran.cjb_agent.service.agent.AgentSessionManager;
import com.ran.cjb_agent.service.log.AgentInteractionLogger;
import com.ran.cjb_agent.service.persistence.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天控制器
 * POST /api/chat → 触发 Agent 异步处理（结果通过 WebSocket 推送）
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AgentOrchestrator agentOrchestrator;
    private final AgentSessionManager sessionManager;
    private final ChatHistoryService chatHistoryService;
    private final AgentInteractionLogger interactionLogger;

    /**
     * 发送消息给 Agent
     * 立即返回 sessionId，Agent 异步处理后通过 WebSocket /topic/session/{sessionId} 推送结果
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> chat(@RequestBody ChatRequest request) {
        // 确保 sessionId 存在
        String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
                ? request.getSessionId()
                : UUID.randomUUID().toString();

        log.info("收到聊天请求 [{}]: {}", sessionId, request.getMessage());

        // 持久化用户消息 + 开始结构化日志记录
        chatHistoryService.saveUserMessage(sessionId, request.getMessage());
        interactionLogger.begin(sessionId, request.getMessage());

        // 异步处理（@Async，立即返回）
        agentOrchestrator.processMessageAsync(
                sessionId,
                request.getMessage(),
                request.getSshConnectionId()
        );

        // 立即返回 sessionId，前端通过 WebSocket 订阅结果
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "status", "processing",
                "message", "消息已接收，正在处理中..."
        ));
    }

    /**
     * 获取所有历史会话列表（供前端会话历史面板展示）
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> listSessions() {
        return ResponseEntity.ok(chatHistoryService.listSessions());
    }

    /**
     * 获取会话历史消息（刷新后恢复聊天记录）
     */
    @GetMapping("/session/{sessionId}/history")
    public ResponseEntity<List<ChatMessageEntity>> getHistory(@PathVariable String sessionId) {
        return ResponseEntity.ok(chatHistoryService.getHistory(sessionId));
    }

    /**
     * 获取 Agent 会话状态
     */
    @GetMapping("/session/{sessionId}/status")
    public ResponseEntity<Map<String, Object>> getSessionStatus(@PathVariable String sessionId) {
        var session = sessionManager.get(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "sessionId", session.getSessionId(),
                "status", session.getStatus().name(),
                "lastActiveAt", session.getLastActiveAt().toString(),
                "osProfile", session.getOsProfile() != null
                        ? session.getOsProfile().getDistro().getDisplayName() + " " + session.getOsProfile().getVersion()
                        : "未探测"
        ));
    }

    /**
     * 清空会话记忆（开启新对话）
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, String>> clearSession(@PathVariable String sessionId) {
        sessionManager.remove(sessionId);
        chatHistoryService.clearHistory(sessionId);
        interactionLogger.remove(sessionId);
        return ResponseEntity.ok(Map.of(
                "message", "会话记忆已清空，下次对话将重新开始。"
        ));
    }
}
