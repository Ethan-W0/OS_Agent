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
    THINKING,
    /** 需要用户在聊天框输入 sudo 密码才能继续执行 */
    SUDO_REQUEST
}
