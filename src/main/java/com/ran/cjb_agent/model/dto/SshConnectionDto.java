package com.ran.cjb_agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;

/**
 * SSH 连接配置 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshConnectionDto {

    /**
     * 连接 ID（更新时必填，创建时留空）
     */
    private String id;

    /**
     * 连接别名
     */
    @NotBlank(message = "连接名称不能为空")
    private String name;

    /**
     * 目标主机
     */
    @NotBlank(message = "主机地址不能为空")
    private String host;

    /**
     * SSH 端口（默认 22）
     */
    @Builder.Default
    private int port = 22;

    /**
     * 用户名
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 密码（与 privateKey 二选一）
     */
    private String password;

    /**
     * 私钥内容（PEM 格式）
     */
    private String privateKey;
}
