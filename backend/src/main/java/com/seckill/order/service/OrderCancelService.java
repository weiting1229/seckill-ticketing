package com.seckill.order.service;

import com.seckill.common.id.IdGenerator;
import com.seckill.common.metrics.SeckillMetrics;
import com.seckill.event.mapper.TicketTypeMapper;
import com.seckill.event.service.StockCache;
import com.seckill.order.domain.Order;
import com.seckill.order.domain.StockLog;
import com.seckill.order.domain.StockLogType;
import com.seckill.order.mapper.OrderMapper;
import com.seckill.order.mapper.StockLogMapper;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 訂單超時取消回補(設計文件第 2、5、7 節)。抽成共用服務:超時取消消費者與兜底排程共用同一段
 * <b>冪等</b>的取消 + 回補邏輯(見 ADR 0005)。
 *
 * <p><b>DB 事務與 Redis 的順序</b>:先於單一事務內完成
 * 「條件 UPDATE 訂單 EXPIRED → 回補 DB 庫存 → 寫 REVERT 流水」,三者原子;
 * <b>事務 commit 之後</b>才回補 Redis 庫存 + 記錄 {@code stock_revert} 指標。理由:
 * <ul>
 *   <li>DB 是最終事實來源;先 commit DB 再動 Redis,避免「Redis 已回補但 DB 事務回滾」的多補。</li>
 *   <li>反向的殘留(DB 已取消、Redis 尚未回補)僅暫時偏少,兜底排程 / 對帳可觀測,且延遲訊息
 *       重投為冪等(條件 UPDATE 影響行數 0 → no-op),不會二次回補。</li>
 * </ul>
 *
 * <p>以 {@link TransactionTemplate} 顯式界定事務邊界(而非同類方法 {@code @Transactional} 自呼叫,
 * 後者會被 Spring 代理繞過而失效),使 Redis 回補確定發生在 commit 之後。
 */
@Service
public class OrderCancelService {

    private static final Logger log = LoggerFactory.getLogger(OrderCancelService.class);

    private final OrderMapper orderMapper;
    private final TicketTypeMapper ticketTypeMapper;
    private final StockLogMapper stockLogMapper;
    private final StockCache stockCache;
    private final SeckillMetrics metrics;
    private final IdGenerator idGenerator;
    private final TransactionTemplate transactionTemplate;

    public OrderCancelService(OrderMapper orderMapper, TicketTypeMapper ticketTypeMapper,
                              StockLogMapper stockLogMapper, StockCache stockCache,
                              SeckillMetrics metrics, IdGenerator idGenerator,
                              PlatformTransactionManager transactionManager) {
        this.orderMapper = orderMapper;
        this.ticketTypeMapper = ticketTypeMapper;
        this.stockLogMapper = stockLogMapper;
        this.stockCache = stockCache;
        this.metrics = metrics;
        this.idGenerator = idGenerator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 取消一筆逾時未支付的訂單並回補庫存;<b>冪等</b>:僅當訂單仍為 PENDING_PAYMENT 時才生效。
     *
     * @return true 本次確實取消並回補;false 訂單已 PAID / 已取消 / 不存在(no-op)
     */
    public boolean cancelExpired(long orderId) {
        // 事務內:條件 UPDATE + DB 回補 + REVERT 流水,原子;回傳被取消的訂單(供 commit 後回補 Redis),
        // 未取消(no-op)則回 null。
        Order cancelled = transactionTemplate.execute(status -> cancelInTx(orderId));
        if (cancelled == null) {
            return false;
        }
        // DB 已 commit,才回補 Redis 庫存與已購集合(對稱於熱路徑 deduct)
        stockCache.revert(cancelled.getTicketTypeId(), cancelled.getUserId());
        // 設計文件第 11 節:理想上 stock_revert 僅來自超時取消
        metrics.recordStockRevert(String.valueOf(cancelled.getTicketTypeId()));
        log.info("訂單超時取消並回補庫存 orderId={} ticketTypeId={} userId={}",
                orderId, cancelled.getTicketTypeId(), cancelled.getUserId());
        return true;
    }

    private Order cancelInTx(long orderId) {
        Instant now = Instant.now();
        int affected = orderMapper.expireIfPending(orderId, now);
        if (affected == 0) {
            return null; // 已終態 / 不存在:冪等 no-op
        }
        Order order = orderMapper.findById(orderId);
        ticketTypeMapper.revertStock(order.getTicketTypeId(), now);

        StockLog stockLog = new StockLog();
        stockLog.setId(idGenerator.nextId());
        stockLog.setTicketTypeId(order.getTicketTypeId());
        stockLog.setOrderId(orderId);
        stockLog.setDelta(1);
        stockLog.setType(StockLogType.REVERT);
        stockLog.setCreatedAt(now);
        stockLogMapper.insert(stockLog);
        return order;
    }
}
