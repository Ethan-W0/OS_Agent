package com.ran.cjb_agent.agent.graph;

import com.ran.cjb_agent.model.domain.OsProfile;
import com.ran.cjb_agent.model.domain.RiskAssessment;
import com.ran.cjb_agent.model.enums.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentState {

    // ===== 输入上下文 =====

    /** 会话 ID */
    private String sessionId;

    /** 用户原始消息 */
    private String userMessage;

    /** 当前绑定的 SSH 连接 ID */
    private String sshConnectionId;

    /** 当前 OS 环境信息 */
    private OsProfile osProfile;

    // ===== 任务分解 =====

    /** IntentParseNode 解析出的多步任务列表（JSON 字符串数组形式） */
    @Builder.Default
    private List<String> taskList = new ArrayList<>();

    /** 当前执行到第几步（0-indexed） */
    @Builder.Default
    private int currentTaskIndex = 0;

    /** 当前步骤的任务描述（自然语言） */
    private String currentTaskDescription;

    /** 当前步骤 OsAdaptNode 生成的具体 Shell 命令 */
    private String currentCommand;

    // ===== 安全检查 =====

    /** SecurityCheckNode 的评估结果 */
    private RiskAssessment riskAssessment;

    /** 从安全评估中提取的风险等级（用于条件路由） */
    @Builder.Default
    private RiskLevel riskLevel = RiskLevel.SAFE;

    /** 二次确认令牌（CRITICAL 时生成，等待用户响应） */
    private String confirmationToken;

    /** 用户确认结果（true=批准，false=拒绝） */
    @Builder.Default
    private boolean userApproved = false;

    // ===== 执行结果 =====

    /** 当前步骤的 SSH 原始执行输出 */
    private String currentRawResult;

    /** 当前步骤 ResultExplainNode 翻译后的自然语言结果 */
    private String currentExplainedResult;

    /** 各步骤结果累积（按序追加） */
    @Builder.Default
    private List<String> stepResults = new ArrayList<>();

    /** 最终汇总文本（SummaryNode 生成） */
    private String finalSummary;

    // ===== 流程控制 =====

    /** 当前节点名称（用于推送进度） */
    private String currentNode;

    /** 是否发生不可恢复的错误 */
    @Builder.Default
    private boolean hasError = false;

    /** 错误信息 */
    private String errorMessage;

    // ===== 便捷方法 =====

    /** 是否还有下一个任务步骤 */
    public boolean hasNextTask() {
        return taskList != null && currentTaskIndex < taskList.size() - 1;
    }

    /** 当前步骤是否为最后一步 */
    public boolean isLastTask() {
        return taskList == null || taskList.isEmpty() || currentTaskIndex >= taskList.size() - 1;
    }

    /** 追加步骤结果（线程安全版本通过节点串行执行保证） */
    public void addStepResult(String result) {
        if (stepResults == null) stepResults = new ArrayList<>();
        stepResults.add(String.format("步骤 %d：%s", currentTaskIndex + 1, result));
    }

    /** 推进到下一步 */
    public void advanceToNextTask() {
        currentTaskIndex++;
        if (taskList != null && currentTaskIndex < taskList.size()) {
            currentTaskDescription = taskList.get(currentTaskIndex);
        }
        // 重置当前步骤状态
        currentCommand = null;
        riskAssessment = null;
        riskLevel = RiskLevel.SAFE;
        confirmationToken = null;
        userApproved = false;
        currentRawResult = null;
        currentExplainedResult = null;
    }
}
