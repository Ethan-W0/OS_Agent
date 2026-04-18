package com.ran.cjb_agent.service.agent;

import com.ran.cjb_agent.model.entity.ChatMessageEntity;
import com.ran.cjb_agent.repository.ChatMessageRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮对话记忆存储
 * sessionId → LangChain4j MessageWindowChatMemory（保留最近 20 条消息）
 * 支持从 MySQL 历史记录重建记忆
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationMemoryStore {

    private static final int MAX_MESSAGES = 20;

    private final ConcurrentHashMap<String, ChatMemory> memories = new ConcurrentHashMap<>();
    private final ChatMessageRepository chatMessageRepository;

    public ChatMemory getOrCreate(String sessionId) {
        return memories.computeIfAbsent(sessionId, id -> rebuildFromDb(id));
    }

    private ChatMemory rebuildFromDb(String sessionId) {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(MAX_MESSAGES);

        try {
            List<ChatMessageEntity> history = chatMessageRepository
                    .findBySessionIdOrderByCreatedAtAsc(sessionId);

            for (ChatMessageEntity msg : history) {
                String type = msg.getType();
                String content = msg.getContent();
                if (content == null || content.isBlank()) continue;

                if ("USER".equals(type)) {
                    memory.add(UserMessage.from(content));
                } else if ("TEXT".equals(type) || "RESULT".equals(type)) {
                    memory.add(AiMessage.from(content));
                }
            }

            if (!history.isEmpty()) {
                log.info("从 MySQL 重建会话记忆 [{}]: 加载 {} 条消息", sessionId, history.size());
            }
        } catch (Exception e) {
            log.warn("重建会话记忆失败 [{}]: {}", sessionId, e.getMessage());
        }

        return memory;
    }

    public void clear(String sessionId) {
        ChatMemory memory = memories.remove(sessionId);
        if (memory != null) {
            memory.clear();
            log.info("会话记忆已清空: {}", sessionId);
        }
    }

    public boolean exists(String sessionId) {
        return memories.containsKey(sessionId);
    }

    public int size() {
        return memories.size();
    }
}
