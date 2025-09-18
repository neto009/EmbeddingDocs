package com.example.demo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "spring.ai.openai.embedding.options")
public class EmbeddingProperties {
    private String model;
    private int dim;
    private int batchSize;
    private long batchSleepMs;
    private Duration requestTimeout;
}
