package com.ran.cjb_agent.service.security;

/**
 * 线程本地会话上下文
 * 在 OsAgentGraph 入口设置当前 sessionId，供工具方法内部使用，
 * 避免将 sessionId 作为 @Tool 参数暴露给 LLM（LLM 无法可靠地传递它）。
 */
public class SessionContextHolder {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    public static void set(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    public static String get() {
        return SESSION_ID.get();
    }

    public static void clear() {
        SESSION_ID.remove();
    }
}
