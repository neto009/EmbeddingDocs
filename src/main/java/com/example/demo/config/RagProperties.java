package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {
    private Search search = new Search();
    private Citation citation = new Citation();

    @Data
    public static class Search {
        private int defaultK = 6;
        private double defaultAlpha = 0.7;
        private int defaultPerDoc = 2;
    }

    @Data
    public static class Citation {
        private int previewLength = 180;
    }
}
