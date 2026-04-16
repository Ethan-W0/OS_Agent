package com.ran.cjb_agent.model.enums;

/**
 * 风险等级枚举
 */
public enum RiskLevel {
    /**
     * 安全：只读或无副作用操作
     */
    SAFE("安全"),

    /**
     * 警告：有副作用但影响可控、可恢复
     */
    WARNING("警告"),

    /**
     * 严重：高风险操作，必须用户二次确认
     */
    CRITICAL("严重"),

    /**
     * 禁止：可能造成系统不可用或数据永久丢失，直接拒绝
     */
    FORBIDDEN("禁止");

    private final String displayName;

    RiskLevel(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
