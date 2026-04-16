package com.ran.cjb_agent.service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 二次确认管理器
 *
 * 核心机制：基于 CompletableFuture 实现任务挂起/恢复/超时熔断
 *
 * 流程：
 * 1. Agent 检测到高危操作 → 调用 waitForConfirmation(token) → 阻塞当前线程
 * 2. 前端收到 RISK_WARNING 消息 → 用户点击"确认"或"拒绝"
 * 3. 前端调用 POST /api/security/confirm → SecurityController.confirm()
 * 4. confirm() 调用 resolve(token, approved) → CompletableFuture.complete(approved)
 * 5. waitForConfirmation() 从阻塞中恢复，返回用户决策
 * 6. 超时未响应 → TimeoutException → 自动熔断，返回 false
 */
@Slf4j
@Component
public class ConfirmationManager {

    @Value("${agent.security.confirmation-timeout-seconds:120}")
    private long confirmationTimeoutSeconds;

    /**
     * 挂起中的确认请求：confirmationToken → CompletableFuture<Boolean>
     * true = 用户批准；false = 用户拒绝或超时
     */
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingMap =
            new ConcurrentHashMap<>();

    /**
     * 挂起当前 Agent 线程，等待用户确认。
     * 此方法会阻塞调用线程直到用户响应或超时。
     *
     * @param confirmationToken 唯一令牌，与前端 RiskWarningCard 绑定
     * @return true = 用户批准执行；false = 用户拒绝或超时熔断
     */
    public boolean waitForConfirmation(String confirmationToken) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingMap.put(confirmationToken, future);
        log.info("高危操作挂起等待确认 [token={}]，超时: {}s", confirmationToken, confirmationTimeoutSeconds);

        try {
            Boolean result = future.get(confirmationTimeoutSeconds, TimeUnit.SECONDS);
            boolean approved = Boolean.TRUE.equals(result);
            log.info("用户确认结果 [token={}]: {}", confirmationToken, approved ? "✅ 批准" : "❌ 拒绝");
            return approved;

        } catch (TimeoutException e) {
            log.warn("确认超时熔断 [token={}]，已自动取消操作", confirmationToken);
            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("确认等待被中断 [token={}]", confirmationToken);
            return false;

        } catch (Exception e) {
            log.error("确认等待异常 [token={}]: {}", confirmationToken, e.getMessage());
            return false;

        } finally {
            pendingMap.remove(confirmationToken);
        }
    }

    /**
     * 用户通过前端响应确认请求。
     * 由 SecurityController.confirm() 调用。
     *
     * @param confirmationToken 确认令牌
     * @param approved          true = 批准；false = 拒绝
     * @return true = 成功响应；false = token 不存在（可能已超时）
     */
    public boolean resolve(String confirmationToken, boolean approved) {
        CompletableFuture<Boolean> future = pendingMap.get(confirmationToken);
        if (future == null) {
            log.warn("确认 token 不存在或已超时: {}", confirmationToken);
            return false;
        }
        future.complete(approved);
        log.info("确认已响应 [token={}]: {}", confirmationToken, approved ? "批准" : "拒绝");
        return true;
    }

    /**
     * 检查某个 token 是否仍在等待确认
     */
    public boolean isPending(String confirmationToken) {
        return pendingMap.containsKey(confirmationToken);
    }

    /**
     * 获取当前挂起的确认数量（用于监控）
     */
    public int getPendingCount() {
        return pendingMap.size();
    }

    /**
     * 强制取消所有挂起的确认（用于 Session 清理）
     */
    public void cancelAll() {
        pendingMap.forEach((token, future) -> {
            future.complete(false);
            log.info("强制取消确认 [token={}]", token);
        });
        pendingMap.clear();
    }
}
