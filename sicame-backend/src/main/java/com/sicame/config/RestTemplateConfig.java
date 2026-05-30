package com.sicame.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Value("${sicame.ml.timeout-ms:30000}")
    private int timeoutMs;

    @Bean("mlRestTemplate")
    public RestTemplate mlRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        // Read timeout must be long enough to cover the full ML retrain (~5-8s)
        // plus network overhead — use 60s to be safe
        factory.setReadTimeout((int) Duration.ofSeconds(60).toMillis());
        // Disable chunked transfer encoding so Python receives a normal
        // Content-Length header instead of a chunked envelope
        factory.setBufferRequestBody(true);
        return new RestTemplate(factory);
    }
}