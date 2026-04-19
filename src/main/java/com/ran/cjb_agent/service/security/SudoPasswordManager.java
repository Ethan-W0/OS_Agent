package com.ran.cjb_agent.service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sudo 密码管理器
 *
 * 当命令需要 sudo 权限且没有可用密码时，挂起 Agent 执行线程，
 * 等待用户在前端聊天框输入 sudo 密码后继续执行。
 *
 * 工作流：
 * 1. Agent 在 executeWithSudo() 中调用 waitForPassword(sessionId) → 线程挂起
 * 2. 前端收到 SUDO_REQUEST，显示密码输入框
 * 3. 用户输入密码 → POST /api/security/sudo-password
 * 4. SecurityController 调用 resolvePassword(sessionId, password) → 线程恢复
 */
@Slf4j
@Component
public class SudoPasswordManager {

    @Value("${agent.security.sudo-timeout-seconds:120}")
    private int sudoTimeoutSeconds;

    /** sessionId → 挂起的密码等待 Future */
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingMap = new ConcurrentHashMap<>();

    /**
     * 挂起当前线程，等待用户输入 sudo 密码。
     *
     * @param sessionId 会话 ID（唯一标识一次等待）
     * @return 用户输入的密码，超时或取消时返回 null
     */
    public String waitForPassword(String sessionId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingMap.put(sessionId, future);
        log.info("等待用户输入 sudo 密码 [session={}]，超时 {}s", sessionId, sudoTimeoutSeconds);
        try {
            return future.get(sudoTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("sudo 密码输入超时 [session={}]", sessionId);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("sudo 密码等待被中断 [session={}]", sessionId);
            return null;
        } catch (Exception e) {
            log.error("sudo 密码等待异常 [session={}]: {}", sessionId, e.getMessage());
            return null;
        } finally {
            pendingMap.remove(sessionId);
        }
    }

    /**
     * 前端提交密码后调用，恢复挂起的 Agent 线程。
     *
     * @param sessionId 会话 ID
     * @param password  用户输入的 sudo 密码
     * @return true 表示成功恢复，false 表示会话不存在（已超时）
     */
    public boolean resolvePassword(String sessionId, String password) {
        CompletableFuture<String> future = pendingMap.get(sessionId);
        if (future == null) {
            log.warn("sudo 密码提交失败：会话 [{}] 不存在或已超时", sessionId);
            return false;
        }
        future.complete(password);
        log.info("sudo 密码已提交 [session={}]", sessionId);
        return true;
    }

    public boolean isPending(String sessionId) {
        return pendingMap.containsKey(sessionId);
    }

    /** 会话清理时强制取消所有等待 */
    public void cancel(String sessionId) {
        CompletableFuture<String> future = pendingMap.remove(sessionId);
        if (future != null) {
            future.complete(null);
            log.info("sudo 密码等待已取消 [session={}]", sessionId);
        }
    }
}
