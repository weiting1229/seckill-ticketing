package com.seckill.order.service;

import com.seckill.order.mapper.OrderMapper;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 逾時訂單兜底排程(belt-and-suspenders,設計文件第 5、7 節)。
 *
 * <p>正常路徑靠延遲佇列(15 分鐘 TTL → DLX → 超時消費者)自動取消;此排程為<b>第二保險</b>:
 * 防延遲訊息遺失(broker 重啟、發送失敗、DLQ 等),定期掃 {@code status='PENDING_PAYMENT'
 * AND expire_at < now}(走部分索引 idx_orders_status_expire),對每筆走與超時消費者<b>相同</b>的
 * 取消回補邏輯 {@link OrderCancelService#cancelExpired}(冪等,與延遲訊息重投互不干擾:條件 UPDATE
 * 影響行數 0 即 no-op,不重複回補)。
 *
 * <p>單次掃描取一批(batch-limit)最舊逾時者;若滿批代表可能積壓,下次排程續掃。以 {@code fixedDelay}
 * 避免前次未完成又重入。
 */
@Component
public class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);

    private final OrderMapper orderMapper;
    private final OrderCancelService cancelService;
    private final int batchLimit;

    public OrderExpiryScheduler(OrderMapper orderMapper, OrderCancelService cancelService,
                                @Value("${order.expiry-sweep.batch-limit:500}") int batchLimit) {
        this.orderMapper = orderMapper;
        this.cancelService = cancelService;
        this.batchLimit = batchLimit;
    }

    @Scheduled(
            fixedDelayString = "${order.expiry-sweep.interval-ms:60000}",
            initialDelayString = "${order.expiry-sweep.initial-delay-ms:60000}")
    public void scheduledSweep() {
        sweepOnce();
    }

    /**
     * 掃一批逾時未支付訂單並取消回補;回傳實際取消筆數。抽為 public 供測試直接呼叫(不依賴排程時序)。
     * 單筆失敗僅記日誌並續掃下一筆,不中止整批。
     */
    public int sweepOnce() {
        List<Long> ids = orderMapper.findExpiredPendingIds(Instant.now(), batchLimit);
        if (ids.isEmpty()) {
            return 0;
        }
        int cancelled = 0;
        for (Long id : ids) {
            try {
                if (cancelService.cancelExpired(id)) {
                    cancelled++;
                }
            } catch (Exception e) {
                log.error("兜底取消單筆失敗 orderId={},續掃下一筆", id, e);
            }
        }
        if (cancelled > 0) {
            log.info("兜底排程取消 {} 筆逾時未支付訂單(掃描候選 {} 筆)", cancelled, ids.size());
        }
        if (ids.size() == batchLimit) {
            log.warn("兜底掃描達批次上限 {},可能有積壓,下次排程續掃", batchLimit);
        }
        return cancelled;
    }
}
