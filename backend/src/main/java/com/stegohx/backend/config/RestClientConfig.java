package com.stegohx.backend.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client used to reach the Python analyzer service.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient analyzerRestClient(AppProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(properties.analyzer().timeoutMs()).toMillis());
        factory.setReadTimeout(properties.analyzer().timeoutMs());
        return RestClient.builder()
                .baseUrl(properties.analyzer().baseUrl())
                .requestFactory(factory)
                .build();
    }
}
