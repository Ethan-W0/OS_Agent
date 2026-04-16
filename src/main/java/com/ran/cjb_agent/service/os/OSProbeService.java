package com.ran.cjb_agent.service.os;

import com.ran.cjb_agent.model.domain.OsProfile;
import com.ran.cjb_agent.model.enums.OsDistro;
import com.ran.cjb_agent.service.ssh.SshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OS 探针服务（核心模块三）
 *
 * 通过 SSH 执行探针命令序列，自动识别 Linux 发行版及版本特征，
 * 构建 OsProfile 并缓存，供后续所有模块使用。
 *
 * 探测顺序：
 * 1. cat /etc/os-release      → 主要来源（标准化，现代发行版均支持）
 * 2. cat /etc/issue           → 备用（老旧发行版）
 * 3. uname -r                 → 内核版本
 * 4. uname -m                 → CPU 架构
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OSProbeService {

    private final SshService sshService;
    private final DistroCommandMapper distroCommandMapper;
    private final OsProfileCache osProfileCache;

    /**
     * 发行版识别规则（按优先级排序）
     */
    private static final Map<String, OsDistro> DISTRO_KEYWORD_MAP = Map.of(
            "openEuler",   OsDistro.OPEN_EULER,
            "CentOS",      OsDistro.CENTOS,
            "Red Hat",     OsDistro.RHEL,
            "Rocky",       OsDistro.ROCKY,
            "AlmaLinux",   OsDistro.ALMA,
            "Ubuntu",      OsDistro.UBUNTU,
            "Debian",      OsDistro.DEBIAN
    );

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("VERSION(?:_ID)?\\s*=\\s*\"?([\\d.A-Za-z ]+?)\"?\\s*(?:\\n|$)");

    /**
     * 探测指定 SSH 连接的操作系统信息
     * 结果自动缓存，相同 connectionId 不重复探测
     *
     * @param connectionId SSH 连接 ID
     * @return 探测到的 OsProfile
     */
    public OsProfile probe(String connectionId) {
        // 优先返回缓存
        if (osProfileCache.contains(connectionId)) {
            log.debug("使用缓存的 OsProfile [{}]", connectionId);
            return osProfileCache.getOrDefault(connectionId);
        }

        log.info("开始探测操作系统环境 [{}]", connectionId);

        try {
            // ===== Step 1: 读取 /etc/os-release =====
            String osRelease = sshService.execute(connectionId,
                    "cat /etc/os-release 2>/dev/null || cat /etc/issue 2>/dev/null || echo 'UNKNOWN'", 10);

            // ===== Step 2: 读取内核和架构信息 =====
            String kernelVersion = sshService.execute(connectionId, "uname -r", 5).trim();
            String arch          = sshService.execute(connectionId, "uname -m", 5).trim();
            String hostname      = sshService.execute(connectionId, "hostname", 5).trim();

            // ===== Step 3: 识别发行版 =====
            OsDistro distro = detectDistro(osRelease);
            String version  = extractVersion(osRelease);

            // ===== Step 4: 获取该发行版推荐命令映射 =====
            Map<String, String> cmdMap = distroCommandMapper.getCommandMap(distro);

            // ===== Step 5: 构建并缓存 OsProfile =====
            OsProfile profile = OsProfile.builder()
                    .distro(distro)
                    .version(version)
                    .kernelVersion(kernelVersion)
                    .arch(arch)
                    .commandMap(cmdMap)
                    .probeTime(Instant.now())
                    .probeSuccess(true)
                    .build();

            osProfileCache.put(connectionId, profile);

            log.info("OS 探测完成 [{}]: {} {} | 内核: {} | 架构: {} | 主机: {}",
                    connectionId, distro.getDisplayName(), version, kernelVersion, arch, hostname);

            return profile;

        } catch (Exception e) {
            log.error("OS 探测失败 [{}]: {}", connectionId, e.getMessage());
            // 探测失败时返回 UNKNOWN profile，使用通用命令集
            OsProfile fallback = OsProfile.builder()
                    .distro(OsDistro.UNKNOWN)
                    .version("Unknown")
                    .kernelVersion("Unknown")
                    .arch("Unknown")
                    .commandMap(distroCommandMapper.getCommandMap(OsDistro.UNKNOWN))
                    .probeTime(Instant.now())
                    .probeSuccess(false)
                    .build();
            osProfileCache.put(connectionId, fallback);
            return fallback;
        }
    }

    /**
     * 强制重新探测（清除缓存后重新执行）
     */
    public OsProfile reprobe(String connectionId) {
        osProfileCache.invalidate(connectionId);
        return probe(connectionId);
    }

    /**
     * 从 /etc/os-release 内容中识别发行版
     */
    private OsDistro detectDistro(String osReleaseContent) {
        for (Map.Entry<String, OsDistro> entry : DISTRO_KEYWORD_MAP.entrySet()) {
            if (osReleaseContent.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return OsDistro.UNKNOWN;
    }

    /**
     * 从 /etc/os-release 中提取版本号
     * 优先取 VERSION_ID，其次取 VERSION
     */
    private String extractVersion(String osReleaseContent) {
        // 先找 VERSION_ID="22.03"
        Pattern versionIdPattern = Pattern.compile("VERSION_ID\\s*=\\s*\"?([^\"\\n]+)\"?");
        Matcher m = versionIdPattern.matcher(osReleaseContent);
        if (m.find()) {
            return m.group(1).trim();
        }
        // 再找 VERSION="22.03 LTS SP3"
        m = VERSION_PATTERN.matcher(osReleaseContent);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "Unknown";
    }
}
