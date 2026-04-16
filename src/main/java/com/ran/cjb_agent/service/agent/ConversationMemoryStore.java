package com.ran.cjb_agent.service.agent;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮对话记忆存储
 * sessionId → LangChain4j MessageWindowChatMemory（保留最近 20 条消息）
 */
@Slf4j
@Component
public class ConversationMemoryStore {

    private static final int MAX_MESSAGES = 20;

    private final ConcurrentHashMap<String, ChatMemory> memories = new ConcurrentHashMap<>();

    /**
     * 获取或创建指定 session 的对话记忆
     */
    public ChatMemory getOrCreate(String sessionId) {
        return memories.computeIfAbsent(sessionId,
                id -> MessageWindowChatMemory.withMaxMessages(MAX_MESSAGES));
    }

    /**
     * 清空指定 session 的对话记忆
     */
    public void clear(String sessionId) {
        ChatMemory memory = memories.remove(sessionId);
        if (memory != null) {
            memory.clear();
            log.info("会话记忆已清空: {}", sessionId);
        }
    }

    /**
     * 检查 session 是否有记忆
     */
    public boolean exists(String sessionId) {
        return memories.containsKey(sessionId);
    }

    /**
     * 获取当前管理的 session 数量
     */
    public int size() {
        return memories.size();
    }
}
