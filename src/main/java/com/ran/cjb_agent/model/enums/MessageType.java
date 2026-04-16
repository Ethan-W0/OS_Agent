package com.ran.cjb_agent.model.enums;

/**
 * WebSocket 消息类型枚举
 */
public enum MessageType {
    /**
     * 普通文本响应
     */
    TEXT,

    /**
     * 高危风险警告（需用户确认）
     */
    RISK_WARNING,

    /**
     * 命令执行预览（展示即将执行的命令）
     */
    COMMAND_PREVIEW,

    /**
     * 执行结果
     */
    RESULT,

    /**
     * 错误信息
     */
    ERROR,

    /**
     * 节点进度（LangGraph4j 状态图节点状态更新）
     */
    NODE_PROGRESS,

    /**
     * 操作被拒绝
     */
    REJECTED,

    /**
     * 流式 token（一次一个 token）
     */
    TOKEN
}
