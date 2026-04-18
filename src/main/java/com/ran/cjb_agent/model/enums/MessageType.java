package com.ran.cjb_agent.model.enums;

/**
 * WebSocket 消息类型枚举
 */
public enum MessageType {
    TEXT,
    RISK_WARNING,
    COMMAND_PREVIEW,
    RESULT,
    ERROR,
    NODE_PROGRESS,
    REJECTED,
    TOKEN,
    /** Agent 意图推理过程（自然语言分析步骤 + 预计执行指令） */
    THINKING
}
