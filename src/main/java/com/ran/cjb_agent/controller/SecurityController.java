package com.ran.cjb_agent.controller;

import com.ran.cjb_agent.model.dto.ConfirmationDto;
import com.ran.cjb_agent.service.security.ConfirmationManager;
import com.ran.cjb_agent.service.security.SudoPasswordManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.Map;

/**
 * 安全确认控制器
 * 前端通过此接口响应高危操作的二次确认请求
 */
@Slf4j
@RestController
@RequestMapping("/api/security")
@RequiredArgsConstructor
public class SecurityController {

    private final ConfirmationManager confirmationManager;
    private final SudoPasswordManager sudoPasswordManager;

    /**
     * 用户确认或拒绝高危操作
     * 前端点击"确认执行"或"取消操作"时调用
     */
    @PostMapping("/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@Valid @RequestBody ConfirmationDto dto) {
        log.info("收到安全确认请求 [session={}] token={} approved={}",
                dto.getSessionId(), dto.getConfirmationToken(), dto.getApproved());

        boolean resolved = confirmationManager.resolve(dto.getConfirmationToken(), dto.getApproved());

        if (!resolved) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "确认令牌已过期或不存在，操作可能已因超时自动取消。"
            ));
        }

        String action = dto.getApproved() ? "已批准执行" : "已拒绝执行";
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", action,
                "confirmationToken", dto.getConfirmationToken()
        ));
    }

    /**
     * 查询当前是否有挂起等待确认的操作
     */
    @GetMapping("/pending/count")
    public ResponseEntity<Map<String, Integer>> getPendingCount() {
        return ResponseEntity.ok(Map.of("pendingCount", confirmationManager.getPendingCount()));
    }

    /**
     * 用户通过聊天框提交 sudo 密码
     * 前端收到 SUDO_REQUEST 消息后，用户输入密码并点击提交时调用
     */
    @PostMapping("/sudo-password")
    public ResponseEntity<Map<String, Object>> submitSudoPassword(
            @RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String password  = body.get("password");

        if (sessionId == null || sessionId.isBlank() || password == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "sessionId 和 password 不能为空"
            ));
        }

        log.info("收到 sudo 密码提交 [session={}]", sessionId);
        boolean resolved = sudoPasswordManager.resolvePassword(sessionId, password);

        if (!resolved) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "当前会话没有等待中的 sudo 请求，可能已超时（120秒）。"
            ));
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "密码已提交，继续执行..."));
    }
}
