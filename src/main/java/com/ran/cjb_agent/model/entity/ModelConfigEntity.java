package com.ran.cjb_agent.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "model_configs")
public class ModelConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 500)
    private String baseUrl;

    @Column(nullable = false, length = 500)
    private String apiKey;

    @Column(nullable = false, length = 100)
    private String modelName;

    @Builder.Default
    @Column(nullable = false)
    private Integer timeoutSeconds = 60;

    @Builder.Default
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
