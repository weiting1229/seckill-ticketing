package com.seckill.config;

import io.lettuce.core.api.StatefulConnection;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

/**
 * 覆寫 Spring Boot 自動配置的 {@link LettuceConnectionFactory}(設計文件外的壓測調優,見
 * load-test-report.md 第 11、13 節)。
 *
 * <p>{@code LettuceConnectionFactory} 預設 {@code shareNativeConnection=true},一般(非阻塞)
 * 指令一律共用同一條連線,{@code spring.data.redis.lettuce.pool.*} 設定對這條共用連線完全不生效
 * ——這點已用 {@code CLIENT LIST} 併發實測證實(80 併發打 /seckill/token,連線數全程維持在 4)。
 * 要讓連線池真的作用於一般指令,必須關閉 {@code shareNativeConnection},而這個開關 Spring Boot
 * 沒有對外暴露成 property,只能自建 bean 呼叫 {@code setShareNativeConnection(false)}。
 *
 * <p>比照 Spring Boot 自動配置在 virtual threads 開啟時的行為(反編譯
 * {@code LettuceConnectionConfiguration} 確認):額外掛上 virtual-thread 版的
 * {@link SimpleAsyncTaskExecutor},避免自建 bean 蓋掉自動配置後,不小心讓這個最佳化跑掉。
 *
 * <p><b>注意</b>:這顆 bean 本身運作正常(用 {@code clientName} 標記驗證過,連線池確實被使用),
 * 但後續用 300 併發真實壓力測試(非序列化的 curl 迴圈)證實,即使連線池可用,實際同時借用的連線數
 * 依然沒有超過個位數——代表 Redis 連線數量很可能不是壓測觀察到的長尾延遲(load-test-report.md
 * 第 11 節)的真正瓶頸。保留這顆 bean 是因為它本身沒有副作用、且未來若真的遇到更高併發仍有機會
 * 用到,但**不要**把它當作長尾延遲問題已解決的證據,詳見第 13 節。
 */
@Configuration
public class RedisConfig {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(RedisProperties props) {
        RedisStandaloneConfiguration standaloneConfig =
                new RedisStandaloneConfiguration(props.getHost(), props.getPort());
        standaloneConfig.setDatabase(props.getDatabase());
        if (props.getPassword() != null && !props.getPassword().isEmpty()) {
            standaloneConfig.setPassword(props.getPassword());
        }

        LettuceClientConfiguration clientConfig = buildClientConfiguration(props);

        LettuceConnectionFactory factory = new LettuceConnectionFactory(standaloneConfig, clientConfig);
        factory.setShareNativeConnection(false);

        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("redis-");
        executor.setVirtualThreads(true);
        factory.setExecutor(executor);

        return factory;
    }

    private LettuceClientConfiguration buildClientConfiguration(RedisProperties props) {
        RedisProperties.Pool pool = props.getLettuce().getPool();
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder;
        if (pool != null && Boolean.TRUE.equals(pool.getEnabled())) {
            GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
            poolConfig.setMaxTotal(pool.getMaxActive());
            poolConfig.setMaxIdle(pool.getMaxIdle());
            poolConfig.setMinIdle(pool.getMinIdle());
            if (pool.getMaxWait() != null) {
                poolConfig.setMaxWait(pool.getMaxWait());
            }
            builder = LettucePoolingClientConfiguration.builder().poolConfig(poolConfig);
        } else {
            builder = LettuceClientConfiguration.builder();
        }
        if (props.getTimeout() != null) {
            builder = builder.commandTimeout(props.getTimeout());
        }
        return builder.build();
    }
}
