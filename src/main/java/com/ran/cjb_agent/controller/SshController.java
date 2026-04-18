package com.ran.cjb_agent.controller;

import com.jcraft.jsch.JSchException;
import com.ran.cjb_agent.model.domain.OsProfile;
import com.ran.cjb_agent.model.domain.SshConnectionInfo;
import com.ran.cjb_agent.model.dto.SshConnectionDto;
import com.ran.cjb_agent.service.os.OSProbeService;
import com.ran.cjb_agent.service.os.OsProfileCache;
import com.ran.cjb_agent.service.ssh.SshConnectionPool;
import com.ran.cjb_agent.service.ssh.SshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.*;

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
    private final OsProfileCache osProfileCache;

    /**
     * 获取所有 SSH 连接列表（含实时状态和 OS 信息）
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SshConnectionInfo info : connectionPool.getAllConnectionInfos()) {
            boolean alive = connectionPool.isConnected(info.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", info.getId());
            item.put("name", info.getName());
            item.put("host", info.getHost());
            item.put("port", info.getPort());
            item.put("username", info.getUsername());
            item.put("connected", alive);
            if (osProfileCache.contains(info.getId())) {
                OsProfile p = osProfileCache.getOrDefault(info.getId());
                item.put("osInfo", p.getDistro().getDisplayName() + " " + p.getVersion());
            }
            result.add(item);
        }
        return ResponseEntity.ok(result);
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

            // 采集环境信息
            String envInfo = collectEnvironmentInfo(id, profile);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("connected", true);
            result.put("osInfo", profile.getDistro().getDisplayName() + " " + profile.getVersion());
            result.put("envInfo", envInfo);
            result.put("message", "连接成功，已识别操作系统：" + profile.getDistro().getDisplayName());
            return ResponseEntity.ok(result);
        } catch (JSchException e) {
            log.error("SSH 连接失败 [{}@{}:{}]: {}", dto.getUsername(), dto.getHost(), dto.getPort(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "connected", false,
                    "message", "连接失败：" + e.getMessage()
            ));
        }
    }

    private String collectEnvironmentInfo(String connectionId, OsProfile profile) {
        try {
            String whoami = sshService.execute(connectionId, "whoami", 5).trim();
            String pwd = sshService.execute(connectionId, "pwd", 5).trim();
            String dfRoot = sshService.execute(connectionId, "df -h / | tail -1", 5).trim();
            String topProc = sshService.execute(connectionId, "ps aux --sort=-%cpu | head -6", 10).trim();

            // 解析磁盘使用率
            String diskUsage = dfRoot;
            String[] dfParts = dfRoot.split("\\s+");
            if (dfParts.length >= 5) {
                diskUsage = String.format("总量 %s，已用 %s，可用 %s，使用率 %s",
                        dfParts[1], dfParts[2], dfParts[3], dfParts[4]);
            }

            // 检查异常进程（CPU > 90%）
            String abnormal = "无异常进程占用";
            String highCpu = sshService.execute(connectionId,
                    "ps aux --sort=-%cpu | awk 'NR>1 && $3>90 {print $11, $3\"%\"}' | head -3", 5).trim();
            if (!highCpu.isEmpty()) {
                abnormal = "高 CPU 占用进程：\n" + highCpu;
            }

            return String.format(
                    "**当前系统** —— %s %s（内核 %s，架构 %s）\n" +
                    "**登录用户** —— %s\n" +
                    "**当前工作目录** —— `%s`\n" +
                    "**根目录磁盘使用** —— %s\n" +
                    "**进程状态** —— %s",
                    profile.getDistro().getDisplayName(), profile.getVersion(),
                    profile.getKernelVersion(), profile.getArch(),
                    whoami, pwd, diskUsage, abnormal
            );
        } catch (Exception e) {
            log.warn("环境信息采集失败 [{}]: {}", connectionId, e.getMessage());
            return String.format("**当前系统** —— %s %s\n（环境详情采集失败：%s）",
                    profile.getDistro().getDisplayName(), profile.getVersion(), e.getMessage());
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
