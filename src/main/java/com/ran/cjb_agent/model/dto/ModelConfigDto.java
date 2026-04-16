package com.ran.cjb_agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型配置 DTO（运行时动态更新 LLM 配置）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfigDto {

    /**
     * LLM API 基础 URL（兼容所有 OpenAI-compatible 服务）
     */
    private String baseUrl;

    /**
     * API Key
     */
    private String apiKey;

    /**
     * 模型名称（如 gpt-4o、deepseek-chat 等）
     */
    private String modelName;

    /**
     * 超时时间（秒）
     */
    private Integer timeoutSeconds;
}
