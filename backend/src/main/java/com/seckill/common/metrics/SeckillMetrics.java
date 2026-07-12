package com.seckill.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 搶購業務指標(設計文件第 11 節)。置於 common 供 seckill(熱路徑)與 order(消費者)共用,
 * 避免模組循環依賴。
 *
 * <p>Meter 以 Micrometer 慣例的 dot 命名,由 Prometheus registry 轉為設計指定的匯出名:
 * <ul>
 *   <li>{@code seckill.requests} → {@code seckill_requests_total{result,ticket_type_id}}</li>
 *   <li>{@code seckill.order.create.duration} → {@code seckill_order_create_duration_seconds}(histogram)</li>
 *   <li>{@code seckill.stock.revert} → {@code seckill_stock_revert_total{ticket_type_id}}</li>
 * </ul>
 * {@code seckill_redis_stock}(gauge)由 StockGaugePublisher 定時同步。
 */
@Component
public class SeckillMetrics {

    public static final String TICKET_TYPE_UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public SeckillMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 累加搶購請求計數;result 例:success / sold_out / duplicate / rate_limited / invalid_token。 */
    public void incrementRequest(String result, String ticketTypeId) {
        registry.counter("seckill.requests", "result", result, "ticket_type_id", ticketTypeId).increment();
    }

    /** 被限流:result=rate_limited,ticket_type_id 未知(限流發生在請求前緣)。 */
    public void rateLimited() {
        incrementRequest("rate_limited", TICKET_TYPE_UNKNOWN);
    }

    /** 從發 MQ 到訂單落庫的耗時(以訊息 timestamp 計);負值(時鐘偏移)clamp 為 0。 */
    public void recordOrderCreateDuration(String ticketTypeId, long durationMillis) {
        Timer.builder("seckill.order.create.duration")
                .description("從發 MQ 到訂單落庫耗時")
                .publishPercentileHistogram()
                .tag("ticket_type_id", ticketTypeId)
                .register(registry)
                .record(Math.max(0, durationMillis), TimeUnit.MILLISECONDS);
    }

    /** 庫存回補次數(異常訊號:MQ 發送失敗 / DB 售罄 / 超時取消)。 */
    public void recordStockRevert(String ticketTypeId) {
        registry.counter("seckill.stock.revert", "ticket_type_id", ticketTypeId).increment();
    }
}
