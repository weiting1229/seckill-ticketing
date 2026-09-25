package com.seckill.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 限流專用的 Redis(Lettuce)連線(ADR 0010 §6)。
 *
 * <p>為什麼不走 {@code StringRedisTemplate}:{@link RedisConfig} 關閉了 {@code shareNativeConnection}
 * 且未啟用連線池,實測每次 template 呼叫都會新開一條 TCP 連線(200 次呼叫 {@code total_connections_received}
 * 增加 201)。限流在每個搶購請求的最前緣,3000/s 就是每秒 3000 次連線建立。
 * 這裡依 {@code spring.data.redis.*} 另建<b>一條長駐、多工共用</b>的連線,與階段 5 bench 量測的形狀相同
 * (單一共享 {@code StatefulRedisConnection},報告 §14.7)。
 */
@Configuration
public class RateLimitConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient(RedisProperties props) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(props.getHost())
                .withPort(props.getPort())
                .withDatabase(props.getDatabase());
        if (props.getPassword() != null && !props.getPassword().isEmpty()) {
            builder.withPassword(props.getPassword().toCharArray());
        }
        if (props.getTimeout() != null) {
            builder.withTimeout(props.getTimeout());
        }
        return RedisClient.create(builder.build());
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, String> rateLimitRedisConnection(RedisClient rateLimitRedisClient) {
        return rateLimitRedisClient.connect();
    }
}
