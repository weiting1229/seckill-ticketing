package com.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.seckill.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * M0 健康檢查與預設安全策略驗證。整合測試環境(含 Flyway 遷移)由 {@link AbstractIntegrationTest} 提供。
 */
class HealthEndpointIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void healthEndpointShouldBeAnonymouslyAccessible() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"code\":0");
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void actuatorHealthShouldReportUpIncludingMiddleware() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void unknownEndpointShouldReturn401InUnifiedFormat() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/whatever", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":1401");
    }
}
