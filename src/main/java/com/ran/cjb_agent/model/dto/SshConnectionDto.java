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

    @NotBlank(message = "连接名称不能为空")
    private String name;

    @NotBlank(message = "主机地址不能为空")
    private String host;

    @Builder.Default
    private int port = 22;

    @NotBlank(message = "用户名不能为空")
    private String username;

    private String password;

    private String privateKey;

    /**
     * 当前会话 ID（连接成功后通过 WebSocket 推送环境信息）
     */
    private String sessionId;
}
