package com.ran.cjb_agent.model.dto;

import com.ran.cjb_agent.model.enums.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 高危风险警告 DTO（推送给前端显示风险确认卡）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskWarningDto {

    /**
     * 风险等级
     */
    private RiskLevel level;

    /**
     * 待执行的危险命令
     */
    private String command;

    /**
     * 安全评估理由（LLM 生成的中文可解释说明）
     */
    private String rationale;

    /**
     * 更安全的替代方案（可选）
     */
    private String suggestedAlternative;

    /**
     * 确认令牌（前端回调时附带此 token）
     */
    private String confirmationToken;

    /**
     * 确认超时秒数（前端显示倒计时）
     */
    @Builder.Default
    private int timeoutSeconds = 120;
}
