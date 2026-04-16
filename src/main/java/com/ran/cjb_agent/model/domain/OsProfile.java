package com.ran.cjb_agent.model.domain;

import com.ran.cjb_agent.model.enums.OsDistro;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Linux 操作系统 Profile：通过 SSH 探针探测得到
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OsProfile {

    /**
     * 发行版类型
     */
    private OsDistro distro;

    /**
     * 版本号（如 "22.03 LTS SP3"）
     */
    private String version;

    /**
     * 内核版本
     */
    private String kernelVersion;

    /**
     * CPU 架构（x86_64 / aarch64）
     */
    private String arch;

    /**
     * 该发行版推荐命令映射表
     * key: 操作类型（如 "diskUsage", "packageManager"）
     * value: 具体命令或路径
     */
    @Builder.Default
    private Map<String, String> commandMap = new HashMap<>();

    /**
     * 探测时间
     */
    private Instant probeTime;

    /**
     * 是否探测成功
     */
    @Builder.Default
    private boolean probeSuccess = false;
}
