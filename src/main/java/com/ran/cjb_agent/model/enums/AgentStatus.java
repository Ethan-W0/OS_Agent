package com.ran.cjb_agent.model.enums;

/**
 * Agent 会话状态枚举
 */
public enum AgentStatus {
    /**
     * 空闲：等待用户指令
     */
    IDLE,

    /**
     * 运行中：正在处理任务
     */
    RUNNING,

    /**
     * 已挂起：等待用户二次确认高危操作
     */
    SUSPENDED,

    /**
     * 错误状态
     */
    ERROR
}
