package com.seckill.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.seckill.common.metrics.SeckillMetrics;
import com.seckill.event.service.StockGaugePublisher;
import com.seckill.support.AbstractAdminIntegrationTest;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

/**
 * 監控埋點(子項 G)驗證:觸發各業務指標後 scrape Prometheus registry,斷言<b>匯出名稱</b>
 * 與設計文件第 11 節一致(確認無 _total / _seconds 雙後綴)。
 *
 * <p>此處直接觸發各 meter(其在真實流程的呼叫由 e2e / consumer 測試涵蓋),避免依賴跨 context
 * 的非同步消費——本測試專注驗證 Prometheus 匯出名稱正確。
 *
 * <p>{@code @AutoConfigureObservability}:@SpringBootTest 預設關閉 metrics export registry(加速測試),
 * 正式環境不受影響;此處需啟用才能取得 PrometheusMeterRegistry。
 */
@AutoConfigureObservability
class SeckillMetricsIT extends AbstractAdminIntegrationTest {

    @Autowired
    SeckillMetrics metrics;

    @Autowired
    StockGaugePublisher stockGaugePublisher;

    @Autowired
    PrometheusMeterRegistry prometheusRegistry;

    @Test
    void businessMetricsAreExposedWithExpectedNames() {
        String admin = createAdminToken();
        long ttId = createTicketType(admin, createEvent(admin), 50);
        warmup(admin, ttId); // ONLINE + Redis 庫存,供 gauge 同步
        String tt = String.valueOf(ttId);

        metrics.incrementRequest("success", tt);
        metrics.incrementRequest("sold_out", tt);
        metrics.recordOrderCreateDuration(tt, 12);
        metrics.recordStockRevert(tt);
        stockGaugePublisher.syncStockGauges(); // 註冊/更新 seckill_redis_stock gauge

        String body = prometheusRegistry.scrape();
        assertThat(body).contains(
                "seckill_requests_total",
                "seckill_order_create_duration_seconds",
                "seckill_stock_revert_total",
                "seckill_redis_stock",
                "id_generator_clock_backwards_total");
        // 確認無雙後綴
        assertThat(body).doesNotContain("seckill_requests_total_total");
        assertThat(body).doesNotContain("id_generator_clock_backwards_total_total");
        // gauge 標籤與值(該票種 Redis 剩餘 50)
        assertThat(body).contains("ticket_type_id=\"" + ttId + "\"");
    }

    private String createEvent(String admin) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", "指標測試");
        m.put("venue", "Taipei");
        m.put("eventTime", Instant.now().plus(10, ChronoUnit.DAYS).toString());
        return json(post("/api/v1/admin/events", admin, m)).path("data").path("id").asText();
    }

    private long createTicketType(String admin, String eventId, int totalStock) {
        Map<String, Object> m = new HashMap<>();
        m.put("eventId", eventId);
        m.put("name", "看台");
        m.put("price", "800.00");
        m.put("totalStock", totalStock);
        m.put("seckillStart", Instant.now().minus(1, ChronoUnit.HOURS).toString());
        m.put("seckillEnd", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        return Long.parseLong(json(post("/api/v1/admin/ticket-types", admin, m)).path("data").path("id").asText());
    }

    private void warmup(String admin, long ttId) {
        post("/api/v1/admin/ticket-types/" + ttId + "/warmup", admin, null);
    }
}
