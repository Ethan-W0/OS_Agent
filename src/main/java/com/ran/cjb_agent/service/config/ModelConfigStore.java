package com.ran.cjb_agent.service.config;

import com.ran.cjb_agent.model.dto.ModelConfigDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型配置存储器（运行时热更新）
 * 使用 AtomicReference 保证线程安全，无需重启即可切换模型/接口
 */
@Slf4j
@Component
public class ModelConfigStore {

    /**
     * 当前生效的模型配置（原子引用，保证并发安全）
     */
    private final AtomicReference<ModelConfigDto> currentConfig = new AtomicReference<>();

    public ModelConfigStore(
            @Value("${agent.llm.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${agent.llm.api-key:your-api-key-here}") String apiKey,
            @Value("${agent.llm.model-name:gpt-4o}") String modelName,
            @Value("${agent.llm.timeout-seconds:60}") int timeoutSeconds) {

        currentConfig.set(ModelConfigDto.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeoutSeconds(timeoutSeconds)
                .build());

        log.info("模型配置初始化: model={}, baseUrl={}", modelName, baseUrl);
    }

    public ModelConfigDto get() {
        return currentConfig.get();
    }

    /**
     * 运行时更新模型配置（热更新，立即生效）
     */
    public void update(ModelConfigDto newConfig) {
        ModelConfigDto old = currentConfig.getAndSet(newConfig);
        log.info("模型配置已更新: {} → {}", old.getModelName(), newConfig.getModelName());
    }
}
