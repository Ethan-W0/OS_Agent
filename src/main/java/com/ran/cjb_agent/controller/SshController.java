package com.ran.cjb_agent.controller;

import com.jcraft.jsch.JSchException;
import com.ran.cjb_agent.model.domain.SshConnectionInfo;
import com.ran.cjb_agent.model.dto.SshConnectionDto;
import com.ran.cjb_agent.service.os.OSProbeService;
import com.ran.cjb_agent.service.ssh.SshConnectionPool;
import com.ran.cjb_agent.service.ssh.SshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * SSH 连接管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/ssh/connections")
@RequiredArgsConstructor
public class SshController {

    private final SshConnectionPool connectionPool;
    private final SshService sshService;
    private final OSProbeService osProbeService;

    /**
     * 获取所有 SSH 连接列表
     */
    @GetMapping
    public ResponseEntity<Collection<SshConnectionInfo>> list() {
        return ResponseEntity.ok(connectionPool.getAllConnectionInfos());
    }

    /**
     * 添加并建立新的 SSH 连接
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@Valid @RequestBody SshConnectionDto dto) {
        String id = UUID.randomUUID().toString();

        SshConnectionInfo info = SshConnectionInfo.builder()
                .id(id)
                .name(dto.getName())
                .host(dto.getHost())
                .port(dto.getPort())
                .username(dto.getUsername())
                .password(dto.getPassword())
                .privateKey(dto.getPrivateKey())
                .build();

        try {
            connectionPool.connect(info);
            info.setConnected(true);

            // 建立连接后自动探测 OS 环境
            var profile = osProbeService.probe(id);

            return ResponseEntity.ok(Map.of(
                    "id", id,
                    "connected", true,
                    "osInfo", profile.getDistro().getDisplayName() + " " + profile.getVersion(),
                    "message", "连接成功，已识别操作系统：" + profile.getDistro().getDisplayName()
            ));
        } catch (JSchException e) {
            log.error("SSH 连接失败 [{}@{}:{}]: {}", dto.getUsername(), dto.getHost(), dto.getPort(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "connected", false,
                    "message", "连接失败：" + e.getMessage()
            ));
        }
    }

    /**
     * 测试 SSH 连接连通性
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String id) {
        if (!connectionPool.isConnected(id)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "connected", false,
                    "message", "连接不存在或已断开"
            ));
        }
        String result = sshService.testConnection(id);
        boolean ok = !result.startsWith("连接测试失败");
        return ResponseEntity.ok(Map.of(
                "connected", ok,
                "result", result
        ));
    }

    /**
     * 重新探测指定连接的 OS 信息
     */
    @PostMapping("/{id}/probe")
    public ResponseEntity<Map<String, Object>> probe(@PathVariable String id) {
        var profile = osProbeService.reprobe(id);
        return ResponseEntity.ok(Map.of(
                "distro", profile.getDistro().getDisplayName(),
                "version", profile.getVersion(),
                "kernel", profile.getKernelVersion(),
                "arch", profile.getArch(),
                "probeSuccess", profile.isProbeSuccess()
        ));
    }

    /**
     * 断开并删除 SSH 连接
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id) {
        connectionPool.disconnect(id);
        return ResponseEntity.ok(Map.of("message", "连接已断开并删除。"));
    }
}
