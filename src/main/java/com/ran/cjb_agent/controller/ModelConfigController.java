package com.ran.cjb_agent.controller;

import com.ran.cjb_agent.model.dto.ModelConfigDto;
import com.ran.cjb_agent.service.config.ModelConfigStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 模型配置控制器（运行时热更新 LLM 配置）
 */
@RestController
@RequestMapping("/api/config/model")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigStore modelConfigStore;

    @GetMapping
    public ResponseEntity<ModelConfigDto> getConfig() {
        var config = modelConfigStore.get();
        // 脱敏：不返回完整 API Key
        return ResponseEntity.ok(ModelConfigDto.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(maskApiKey(config.getApiKey()))
                .modelName(config.getModelName())
                .timeoutSeconds(config.getTimeoutSeconds())
                .build());
    }

    @PutMapping
    public ResponseEntity<Map<String, String>> updateConfig(@RequestBody ModelConfigDto dto) {
        modelConfigStore.update(dto);
        return ResponseEntity.ok(Map.of(
                "message", "模型配置已更新",
                "model", dto.getModelName(),
                "baseUrl", dto.getBaseUrl()
        ));
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) return "****";
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
