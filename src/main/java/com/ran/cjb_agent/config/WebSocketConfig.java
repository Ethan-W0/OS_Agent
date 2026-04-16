package com.ran.cjb_agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket STOMP 配置
 * 前端通过 SockJS 连接 /ws 端点，订阅 /topic/session/{sessionId} 接收 Agent 推送
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 启用内存消息代理，前端订阅 /topic 前缀的主题
        registry.enableSimpleBroker("/topic", "/queue");
        // 客户端发送消息的前缀（前端 → 后端）
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                // 允许所有跨域（开发阶段）
                .setAllowedOriginPatterns("*")
                // 启用 SockJS 降级支持
                .withSockJS();
    }
}
