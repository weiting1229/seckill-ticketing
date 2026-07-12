package com.seckill.event.service;

import com.seckill.event.mapper.TicketTypeMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis 庫存 gauge 定時同步(設計文件第 11 節:{@code seckill_redis_stock},每 5 秒)。
 *
 * <p>每 5 秒讀出所有 ONLINE 票種的 Redis 剩餘庫存,更新對應 gauge(標籤 {@code ticket_type_id})。
 * 各票種的 gauge 只註冊一次,之後僅更新其持有的 {@link AtomicInteger} 值。
 */
@Component
public class StockGaugePublisher {

    private final TicketTypeMapper ticketTypeMapper;
    private final StockCache stockCache;
    private final MeterRegistry registry;
    private final ConcurrentHashMap<Long, AtomicInteger> gauges = new ConcurrentHashMap<>();

    public StockGaugePublisher(TicketTypeMapper ticketTypeMapper, StockCache stockCache,
                               MeterRegistry registry) {
        this.ticketTypeMapper = ticketTypeMapper;
        this.stockCache = stockCache;
        this.registry = registry;
    }

    @Scheduled(fixedRateString = "${seckill.metrics.stock-gauge-interval-ms:5000}")
    public void syncStockGauges() {
        for (Long ticketTypeId : ticketTypeMapper.findOnlineIds()) {
            Integer stock = stockCache.getStock(ticketTypeId);
            if (stock == null) {
                continue; // 未預熱 / 已過期,略過
            }
            gaugeHolder(ticketTypeId).set(stock);
        }
    }

    private AtomicInteger gaugeHolder(long ticketTypeId) {
        return gauges.computeIfAbsent(ticketTypeId, id -> {
            AtomicInteger holder = new AtomicInteger();
            Gauge.builder("seckill.redis.stock", holder, AtomicInteger::get)
                    .description("各票種 Redis 剩餘庫存")
                    .tag("ticket_type_id", String.valueOf(id))
                    .register(registry);
            return holder;
        });
    }
}
