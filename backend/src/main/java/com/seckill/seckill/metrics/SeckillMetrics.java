package com.seckill.seckill.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 搶購業務指標(設計文件第 11 節)。此處先建 {@code seckill_requests_total};其餘埋點於子項 G 補齊。
 *
 * <p>{@code seckill_requests_total} 的標籤集固定為 {@code result} + {@code ticket_type_id}
 * (Micrometer 要求同名 meter 標籤鍵一致)。限流發生在請求前緣、未必已知 ticketTypeId,
 * 故以 {@code unknown} 佔位。
 */
@Component
public class SeckillMetrics {

    public static final String REQUESTS_TOTAL = "seckill_requests_total";
    public static final String TICKET_TYPE_UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public SeckillMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 累加一次搶購請求計數;result 例:success / sold_out / duplicate / rate_limited / invalid_token。 */
    public void incrementRequest(String result, String ticketTypeId) {
        Counter.builder(REQUESTS_TOTAL)
                .tag("result", result)
                .tag("ticket_type_id", ticketTypeId)
                .register(registry)
                .increment();
    }

    /** 被限流:result=rate_limited,ticket_type_id 未知。 */
    public void rateLimited() {
        incrementRequest("rate_limited", TICKET_TYPE_UNKNOWN);
    }
}
