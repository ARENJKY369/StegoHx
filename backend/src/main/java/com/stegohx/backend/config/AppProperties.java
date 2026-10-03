package com.stegohx.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Engine configuration (prefix {@code stegohx}).
 */
@ConfigurationProperties(prefix = "stegohx")
public record AppProperties(Analyzer analyzer, Cors cors) {

    public record Analyzer(String baseUrl, int timeoutMs) {
    }

    public record Cors(String allowedOrigins) {
    }
}
