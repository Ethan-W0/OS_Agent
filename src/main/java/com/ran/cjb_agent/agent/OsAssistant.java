package com.ran.cjb_agent.agent;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * OS 主执行 Agent 接口（LangChain4j AiService）
 *
 * 该接口通过 LangChain4j AiServices.builder() 动态代理实现，
 * 注入：ChatModel（动态配置）、ChatMemory（多轮对话记忆）、Tools（6大工具类）
 */
public interface OsAssistant {

    /**
     * 处理用户单条消息
     * 系统提示词由 systemMessageProvider 动态注入（携带 OS 环境信息）
     *
     * @param sshConnectionId 当前绑定的 SSH 连接 ID（通过 @V 注入到 prompt 变量）
     * @param userMessage     用户的自然语言指令
     * @return Agent 的响应（含工具调用结果）
     */
    @UserMessage("""
            当前 SSH 连接 ID：{{sshConnectionId}}

            用户指令：{{userMessage}}

            请理解用户意图，调用合适的工具执行操作，并以清晰的中文自然语言汇报执行过程和结果。
            """)
    String chat(@V("sshConnectionId") String sshConnectionId,
                @V("userMessage") String userMessage);
}
