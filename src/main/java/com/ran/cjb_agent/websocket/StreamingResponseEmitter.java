package com.ran.cjb_agent.websocket;

import com.ran.cjb_agent.model.dto.ChatResponse;
import com.ran.cjb_agent.model.dto.RiskWarningDto;
import com.ran.cjb_agent.model.enums.MessageType;
import com.ran.cjb_agent.model.enums.RiskLevel;
import com.ran.cjb_agent.service.persistence.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 流式响应推送器
 * 通过 STOMP WebSocket 将 Agent 执行结果实时推送到前端
 * 前端订阅：/topic/session/{sessionId}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamingResponseEmitter {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatHistoryService chatHistoryService;

    private static final String SESSION_TOPIC_PREFIX = "/topic/session/";

    /**
     * 推送 Agent 意图推理过程
     * content = markdown 格式的推理步骤列表
     * command = 最终选定的 Shell 命令
     */
    public void pushThinking(String sessionId, String content, String command) {
        push(sessionId, ChatResponse.builder()
                .type(MessageType.THINKING)
                .sessionId(sessionId)
                .content(content)
                .command(command)
                .finished(true)
                .build());
    }

    /**
     * 推送普通文本消息
     */
    public void pushText(String sessionId, String content) {
        push(sessionId, ChatResponse.builder()
                .type(MessageType.TEXT)
                .sessionId(sessionId)
                .content(content)
                .finished(true)
                .build());
    }

    /**
     * 推送流式 token（逐字输出）
     */
    public void pushToken(String sessionId, String token, boolean finished) {
        push(sessionId, ChatResponse.builder()
                .type(MessageType.TOKEN)
                .sessionId(sessionId)
                .content(token)
                .finished(finished)
                .build());
    }

    /**
     * 推送高危风险警告（CRITICAL 级别，需用户二次确认）
     */
    public void pushRiskWarning(String sessionId, RiskWarningDto warning) {
        push(sessionId, ChatResponse.builder()
                .type(MessageType.RISK_WARNING)
                .sessionId(sessionId)
                .command(warning.getCommand())
                .riskLevel(warning.getLevel())
                .rationale(warning.getRationale())
                .suggestedAlternative(warning.getSuggestedAlternative())
                .confirmationToken(warning.getConfirmationToken())
                .finished(false)
                .build());
    }

    /**
     * 推送操作拒绝通知（FORBIDDEN 级别，直接阻断）
     */
    public void pushRejected(String sessionId, String command, String rationale) {
        push(sessionId, ChatResponse.builder()
                .type(MessageType.REJECTED)
                .sessionId(sessionId)
                .command(command)
                .riskLevel(RiskLevel.FORBIDDEN)
                .rationale(rationale)
                .finished(true)
                .build());
    }

    /**
     * 推送 WARNING 级别风险提示（不阻断，附加说明后继续执行）
     */
    public void pushWarning(String sessionId, String warningMessage) {
        push(sessionId, ChatResponse.builder()
                .type(MessageType.RISK_WARNING)
                .sessionId(sessionId)
                .riskLevel(RiskLevel.WARNING)
                .rationale(warningMessage)
                .finished(false)
                .build());
    }

    /**
     * 推送命令执行预览（展示即将执行的命令）
     */
    public void pushCommandPreview(String sessionId, String command) {
        push(sessionId, ChatResponse.builder()
                .type(MessageType.COMMAND_PREVIEW)
                .sessionId(sessionId)
                .command(command)
                .finished(false)
                .build());
    }

    /**
     * 推送错误信息
     */
    public void pushError(String sessionId, String errorMessage) {
        push(sessionId, ChatResponse.builder()
                .type(MessageType.ERROR)
                .sessionId(sessionId)
                .content(errorMessage)
                .finished(true)
                .build());
    }

    /**
     * 推送节点进度（LangGraph4j 状态图节点执行状态）
     */
    public void pushNodeProgress(String sessionId, String nodeName, String status) {
        push(sessionId, ChatResponse.builder()
                .type(MessageType.NODE_PROGRESS)
                .sessionId(sessionId)
                .nodeName(nodeName)
                .content(status)
                .finished(false)
                .build());
    }

    /**
     * 推送执行结果
     */
    public void pushResult(String sessionId, String content) {
        push(sessionId, ChatResponse.builder()
                .type(MessageType.RESULT)
                .sessionId(sessionId)
                .content(content)
                .finished(true)
                .build());
    }

    /**
     * 核心推送方法：发送到指定 session 的 STOMP topic
     */
    private void push(String sessionId, ChatResponse response) {
        String destination = SESSION_TOPIC_PREFIX + sessionId;
        try {
            messagingTemplate.convertAndSend(destination, response);
            log.debug("推送消息 [{}] → {}: type={}", sessionId, destination, response.getType());
        } catch (Exception e) {
            log.error("WebSocket 推送失败 [sessionId={}]: {}", sessionId, e.getMessage());
        }
        try {
            chatHistoryService.saveAgentMessage(sessionId, response);
        } catch (Exception e) {
            log.warn("消息持久化失败 [sessionId={}]: {}", sessionId, e.getMessage());
        }
    }
}
