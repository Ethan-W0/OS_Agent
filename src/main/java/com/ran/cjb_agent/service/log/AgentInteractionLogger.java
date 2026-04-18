package com.ran.cjb_agent.service.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 交互结构化日志服务
 *
 * 每次用户请求经历多个节点（OsAdaptNode → ExecutionNode → ResultExplainNode）。
 * 本服务以 sessionId 为键累积各节点数据，在 ResultExplainNode 写入完整日志块。
 * 日志写入 logs/interaction.log（通过 logback INTERACTION_LOG logger）。
 */
@Component
public class AgentInteractionLogger {

    private static final Logger ILOG = LoggerFactory.getLogger("INTERACTION_LOG");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** sessionId → 当前轮次交互上下文 */
    private final ConcurrentHashMap<String, InteractionContext> contexts = new ConcurrentHashMap<>();

    // ── API ─────────────────────────────────────────────────────────────────

    /** 用户发送消息时调用，开始记录一次交互 */
    public void begin(String sessionId, String userInput) {
        contexts.put(sessionId, new InteractionContext(sessionId, userInput));
    }

    /**
     * 意图推理完成时调用（OsAdaptNode 或 AiServices 预思考阶段）
     *
     * @param steps   推理步骤列表
     * @param command 适配后的 Shell 命令（可为空字符串）
     */
    public void recordThinking(String sessionId, List<String> steps, String command) {
        contexts.computeIfPresent(sessionId, (k, ctx) -> {
            ctx.reasoningSteps = steps;
            ctx.command = command;
            return ctx;
        });
    }

    /** SSH 命令执行完成后调用（ExecutionNode） */
    public void recordExecution(String sessionId, String command, String rawResult) {
        contexts.computeIfPresent(sessionId, (k, ctx) -> {
            if (command != null && !command.isBlank()) ctx.command = command;
            ctx.rawResult = rawResult;
            return ctx;
        });
    }

    /**
     * 结果解释完成后调用，写入日志块并重置本轮步骤数据。
     * 对多步任务，每步写一块；sessionId 上下文保留，供下步继续使用。
     */
    public void flush(String sessionId, String explainedResult) {
        InteractionContext ctx = contexts.get(sessionId);
        if (ctx == null) return;

        ctx.explainedResult = explainedResult;
        writeBlock(ctx);

        // Reset step-level fields, keep sessionId/userInput for next step
        ctx.reasoningSteps = null;
        ctx.command = null;
        ctx.rawResult = null;
        ctx.explainedResult = null;
        ctx.timestamp = LocalDateTime.now();   // refresh timestamp per step
    }

    /** 会话清空时清理上下文 */
    public void remove(String sessionId) {
        contexts.remove(sessionId);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void writeBlock(InteractionContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n============================================================\n");
        sb.append("会话 ID : ").append(ctx.sessionId).append("\n");
        sb.append("时    间 : ").append(ctx.timestamp.format(FMT)).append("\n");
        sb.append("用户输入 : \"").append(ctx.userInput).append("\"\n");

        // 意图推理
        if (ctx.reasoningSteps != null && !ctx.reasoningSteps.isEmpty()) {
            sb.append("\n【意图理解过程】\n");
            for (int i = 0; i < ctx.reasoningSteps.size(); i++) {
                sb.append(i + 1).append(". ").append(ctx.reasoningSteps.get(i)).append("\n");
            }
        }

        // 执行命令
        if (ctx.command != null && !ctx.command.isBlank()) {
            sb.append("\n【实际执行指令】\n");
            sb.append("$ ").append(ctx.command).append("\n");
        }

        // 执行结果
        if (ctx.rawResult != null && !ctx.rawResult.isBlank()) {
            sb.append("\n【执行结果反馈】\n");
            sb.append(ctx.rawResult.stripTrailing()).append("\n");
            sb.append("(以上为 Agent 返回给前端的实际文本，推送至 WebSocket)\n");
        } else if (ctx.explainedResult != null && !ctx.explainedResult.isBlank()) {
            // AiServices 路径无 rawResult，记录解释结果
            sb.append("\n【执行结果反馈】\n");
            sb.append(ctx.explainedResult.stripTrailing()).append("\n");
        }

        sb.append("============================================================");
        ILOG.info("{}", sb);
    }

    // ── Data holder ──────────────────────────────────────────────────────────

    private static class InteractionContext {
        final String sessionId;
        final String userInput;
        LocalDateTime timestamp;
        List<String> reasoningSteps;
        String command;
        String rawResult;
        String explainedResult;

        InteractionContext(String sessionId, String userInput) {
            this.sessionId = sessionId;
            this.userInput  = userInput;
            this.timestamp  = LocalDateTime.now();
        }
    }
}
