package com.ran.cjb_agent.agent;

import dev.langchain4j.service.TokenStream;
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
     * 处理用户单条消息（阻塞，供 LangGraph 图路径使用）
     */
    @UserMessage("""
            当前 SSH 连接 ID：{{sshConnectionId}}

            用户指令：{{userMessage}}

            请理解用户意图，调用合适的工具执行操作，并以清晰的中文自然语言汇报执行过程和结果。
            """)
    String chat(@V("sshConnectionId") String sshConnectionId,
                @V("userMessage") String userMessage);

    /**
     * 处理用户单条消息（流式，供 AiServices 路径使用）
     * 返回 TokenStream，调用方通过 onNext/onComplete 订阅 token
     */
    @UserMessage("""
            当前 SSH 连接 ID：{{sshConnectionId}}

            用户指令：{{userMessage}}

            请理解用户意图，调用合适的工具执行操作，并以清晰的中文自然语言汇报执行过程和结果。
            """)
    TokenStream streamChat(@V("sshConnectionId") String sshConnectionId,
                           @V("userMessage") String userMessage);
}
