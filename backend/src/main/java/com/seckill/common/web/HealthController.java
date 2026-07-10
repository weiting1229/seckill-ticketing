package com.seckill.common.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 應用層健康檢查(匿名可存取,供 M0 驗證 CI 與部署煙霧測試)。
 * 中介軟體連線健康由 /actuator/health 負責(僅 Docker 內網)。
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "status", "UP",
                "serverTime", Instant.now().toString()
        ));
    }
}
