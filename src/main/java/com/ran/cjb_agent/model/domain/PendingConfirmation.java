package com.ran.cjb_agent.model.domain;

import com.ran.cjb_agent.model.enums.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * 挂起等待用户确认的高危操作
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingConfirmation {

    /**
     * 确认令牌（UUID），用于前端回调
     */
    private String confirmationToken;

    /**
     * 待确认的命令
     */
    private String command;

    /**
     * 风险等级
     */
    private RiskLevel riskLevel;

    /**
     * 安全评估理由（LLM 生成的中文说明）
     */
    private String rationale;

    /**
     * 更安全的替代方案（可选）
     */
    private String suggestedAlternative;

    /**
     * CompletableFuture：true=用户批准，false=拒绝或超时
     */
    private CompletableFuture<Boolean> future;

    /**
     * 创建时间（用于计算超时倒计时）
     */
    @Builder.Default
    private Instant createdAt = Instant.now();
}
