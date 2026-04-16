package com.ran.cjb_agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步与定时任务配置
 * - CompletableFuture（安全二次确认挂起/恢复）需要独立线程池
 * - @Scheduled（SSH 心跳保活）需要 @EnableScheduling
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * Agent 任务执行线程池
     * 用于 AgentOrchestrator 异步处理用户消息（避免阻塞 WebSocket 线程）
     */
    @Bean(name = "agentTaskExecutor")
    public Executor agentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("agent-task-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 安全确认等待线程池
     * 用于 ConfirmationManager 中 CompletableFuture.get() 阻塞等待
     */
    @Bean(name = "confirmationExecutor")
    public Executor confirmationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("confirm-wait-");
        executor.initialize();
        return executor;
    }
}
