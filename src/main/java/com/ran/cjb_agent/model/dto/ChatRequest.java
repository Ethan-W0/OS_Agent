package com.ran.cjb_agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /**
     * 会话 ID（首次为空，服务端自动生成）
     */
    private String sessionId;

    /**
     * 用户消息内容
     */
    private String message;

    /**
     * 使用的 SSH 连接 ID
     */
    private String sshConnectionId;
}
