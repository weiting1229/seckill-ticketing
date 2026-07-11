package com.seckill.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 設定。secret 必須來自環境變數 JWT_SECRET(至少 256 bit),
 * access 預設 15 分鐘、refresh 預設 7 天(設計文件第 3 節)。
 */
@ConfigurationProperties(prefix = "seckill.jwt")
public record JwtProperties(
        String secret,
        Duration accessTtl,
        Duration refreshTtl
) {
}
