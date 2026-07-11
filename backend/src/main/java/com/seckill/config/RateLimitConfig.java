package com.seckill.config;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bucket4j 限流的 Redis(Lettuce)後端(設計文件第 10 節,防刷第一層)。
 *
 * <p>Bucket4j 的 Lettuce 整合需要一條 {@code StatefulRedisConnection<String, byte[]>};Spring Data
 * 的 {@code LettuceConnectionFactory} 不便直接取得原生連線,故此處<b>依 {@code spring.data.redis.*}
 * 屬性另建一條專用 Lettuce 連線</b>供 Bucket4j 使用(與應用主連線分離)。桶狀態存於 Redis,故限流為
 * 分散式(未來多實例共享同一上限)。
 */
@Configuration
public class RateLimitConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient bucket4jRedisClient(RedisProperties props) {
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
    public StatefulRedisConnection<String, byte[]> bucket4jRedisConnection(RedisClient bucket4jRedisClient) {
        // Bucket4j 要求 key=String、value=byte[] 的 codec
        return bucket4jRedisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    public ProxyManager<String> bucket4jProxyManager(
            StatefulRedisConnection<String, byte[]> bucket4jRedisConnection) {
        return Bucket4jLettuce.casBasedBuilder(bucket4jRedisConnection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                .build();
    }
}
