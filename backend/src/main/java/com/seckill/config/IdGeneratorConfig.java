package com.seckill.config;

import com.seckill.common.id.IdGenerator;
import com.seckill.common.id.SnowflakeIdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 將 Snowflake ID 產生器註冊為單例 bean。
 * workerId / datacenterId 來自環境變數(單機 MVP 預設 0);
 * 未來多實例改為啟動時從 Redis INCR 分配,替換此處即可。
 */
@Configuration
public class IdGeneratorConfig {

    @Bean
    public IdGenerator idGenerator(
            @Value("${seckill.id.worker-id:0}") long workerId,
            @Value("${seckill.id.datacenter-id:0}") long datacenterId,
            MeterRegistry meterRegistry) {
        return new SnowflakeIdGenerator(workerId, datacenterId, meterRegistry);
    }
}
