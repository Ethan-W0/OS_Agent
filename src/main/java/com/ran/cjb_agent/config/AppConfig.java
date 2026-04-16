package com.ran.cjb_agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通用 Bean 配置
 */
@Configuration
public class AppConfig {

    /**
     * Jackson ObjectMapper：支持 Java 8 时间类型、枚举序列化
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // 支持 Instant、LocalDate 等 Java 8 时间类型
        mapper.registerModule(new JavaTimeModule());
        // 时间类型序列化为 ISO-8601 字符串，而非时间戳
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
