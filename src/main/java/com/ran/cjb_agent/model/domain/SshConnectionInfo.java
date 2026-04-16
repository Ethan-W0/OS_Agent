package com.ran.cjb_agent.model.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSH 连接信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshConnectionInfo {

    /**
     * 连接唯一 ID
     */
    private String id;

    /**
     * 连接别名（用户自定义名称）
     */
    private String name;

    /**
     * 目标主机（IP 或域名）
     */
    private String host;

    /**
     * SSH 端口（默认 22）
     */
    @Builder.Default
    private int port = 22;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码（与 privateKey 二选一）
     */
    private String password;

    /**
     * 私钥内容（PEM 格式，与 password 二选一）
     */
    private String privateKey;

    /**
     * 是否当前活跃连接
     */
    @Builder.Default
    private boolean connected = false;

    /**
     * 最后连接时间
     */
    private java.time.Instant lastConnectedAt;
}
