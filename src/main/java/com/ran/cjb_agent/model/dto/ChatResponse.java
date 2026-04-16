package com.ran.cjb_agent.model.dto;

import com.ran.cjb_agent.model.enums.MessageType;
import com.ran.cjb_agent.model.enums.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天响应 DTO（通过 WebSocket 推送到前端）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /**
     * 消息类型
     */
    private MessageType type;

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 文本内容（TEXT / ERROR / TOKEN 类型使用）
     */
    private String content;

    /**
     * 高危操作命令（RISK_WARNING 类型使用）
     */
    private String command;

    /**
     * 风险等级（RISK_WARNING 类型使用）
     */
    private RiskLevel riskLevel;

    /**
     * 安全评估理由（RISK_WARNING 类型使用）
     */
    private String rationale;

    /**
     * 更安全的替代方案（可选，RISK_WARNING 类型使用）
     */
    private String suggestedAlternative;

    /**
     * 确认令牌（RISK_WARNING 类型使用，用于二次确认回调）
     */
    private String confirmationToken;

    /**
     * 节点名称（NODE_PROGRESS 类型使用）
     */
    private String nodeName;

    /**
     * 是否为最后一条消息（流式输出结束标志）
     */
    private boolean finished;
}
