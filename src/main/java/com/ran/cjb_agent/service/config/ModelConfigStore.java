package com.ran.cjb_agent.service.config;

import com.ran.cjb_agent.model.dto.ModelConfigDto;
import com.ran.cjb_agent.model.entity.ModelConfigEntity;
import com.ran.cjb_agent.repository.ModelConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型配置存储器（运行时热更新 + MySQL 持久化）
 */
@Slf4j
@Component
public class ModelConfigStore {

    private final AtomicReference<ModelConfigDto> currentConfig = new AtomicReference<>();
    private final ModelConfigRepository configRepository;

    public ModelConfigStore(
            @Value("${agent.llm.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${agent.llm.api-key:your-api-key-here}") String apiKey,
            @Value("${agent.llm.model-name:gpt-4o}") String modelName,
            @Value("${agent.llm.timeout-seconds:60}") int timeoutSeconds,
            ModelConfigRepository configRepository) {

        this.configRepository = configRepository;

        // Try to load from MySQL first, fall back to properties
        ModelConfigDto loaded = loadFromDb();
        if (loaded != null) {
            currentConfig.set(loaded);
            log.info("从 MySQL 恢复模型配置: model={}, baseUrl={}", loaded.getModelName(), loaded.getBaseUrl());
        } else {
            ModelConfigDto defaults = ModelConfigDto.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .timeoutSeconds(timeoutSeconds)
                    .build();
            currentConfig.set(defaults);
            saveToDb(defaults);
            log.info("模型配置初始化: model={}, baseUrl={}", modelName, baseUrl);
        }
    }

    public ModelConfigDto get() {
        return currentConfig.get();
    }

    public void update(ModelConfigDto newConfig) {
        ModelConfigDto old = currentConfig.getAndSet(newConfig);
        saveToDb(newConfig);
        log.info("模型配置已更新: {} → {}", old.getModelName(), newConfig.getModelName());
    }

    private ModelConfigDto loadFromDb() {
        try {
            return configRepository.findTopByOrderByUpdatedAtDesc()
                    .map(e -> ModelConfigDto.builder()
                            .baseUrl(e.getBaseUrl())
                            .apiKey(e.getApiKey())
                            .modelName(e.getModelName())
                            .timeoutSeconds(e.getTimeoutSeconds())
                            .build())
                    .orElse(null);
        } catch (Exception e) {
            log.warn("从 MySQL 加载模型配置失败: {}", e.getMessage());
            return null;
        }
    }

    private void saveToDb(ModelConfigDto config) {
        try {
            ModelConfigEntity entity = configRepository.findTopByOrderByUpdatedAtDesc()
                    .orElse(new ModelConfigEntity());
            entity.setBaseUrl(config.getBaseUrl());
            entity.setApiKey(config.getApiKey());
            entity.setModelName(config.getModelName());
            entity.setTimeoutSeconds(config.getTimeoutSeconds());
            entity.setUpdatedAt(Instant.now());
            configRepository.save(entity);
        } catch (Exception e) {
            log.warn("模型配置持久化失败: {}", e.getMessage());
        }
    }
}
