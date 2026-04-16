package com.ran.cjb_agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 用户确认/拒绝高危操作的请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmationDto {

    /**
     * 确认令牌（与 RiskWarningDto.confirmationToken 对应）
     */
    @NotBlank(message = "confirmationToken 不能为空")
    private String confirmationToken;

    /**
     * 会话 ID
     */
    @NotBlank(message = "sessionId 不能为空")
    private String sessionId;

    /**
     * true = 用户批准执行；false = 用户拒绝
     */
    @NotNull(message = "approved 不能为 null")
    private Boolean approved;
}
