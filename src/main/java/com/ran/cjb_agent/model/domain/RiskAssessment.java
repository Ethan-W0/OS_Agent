package com.ran.cjb_agent.model.domain;

import com.ran.cjb_agent.model.enums.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 安全风险评估结果（由 SecurityAssessmentAgent LLM 或 RiskRuleEngine 生成）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessment {

    /**
     * 风险等级
     */
    private RiskLevel level;

    /**
     * 风险判定理由（中文，面向用户的可解释说明）
     */
    private String rationale;

    /**
     * 更安全的替代方案（可选）
     */
    private String suggestedAlternative;

    /**
     * 原始命令
     */
    private String command;

    // ===== 工厂方法 =====

    public static RiskAssessment safe(String command) {
        return RiskAssessment.builder()
                .level(RiskLevel.SAFE)
                .command(command)
                .rationale("该操作为只读或无副作用操作，安全可直接执行。")
                .build();
    }

    public static RiskAssessment warning(String command, String rationale) {
        return RiskAssessment.builder()
                .level(RiskLevel.WARNING)
                .command(command)
                .rationale(rationale)
                .build();
    }

    public static RiskAssessment critical(String command, String rationale) {
        return RiskAssessment.builder()
                .level(RiskLevel.CRITICAL)
                .command(command)
                .rationale(rationale)
                .build();
    }

    public static RiskAssessment forbidden(String command, String rationale) {
        return RiskAssessment.builder()
                .level(RiskLevel.FORBIDDEN)
                .command(command)
                .rationale(rationale)
                .build();
    }
}
