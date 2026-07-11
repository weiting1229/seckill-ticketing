package com.seckill.event.service;

import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.event.domain.TicketType;
import com.seckill.event.dto.ReconcileResponse;
import com.seckill.event.mapper.ReconcileMapper;
import com.seckill.event.mapper.TicketTypeMapper;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 庫存對帳(設計文件第 9、13 節)。比對四方並標示是否一致:
 * DB stock_remaining、Redis 剩餘、有效訂單數(PAID+PENDING)、stock_logs 淨值。
 *
 * <p>一致的充要條件(零超賣、零漏帳):
 * <pre>
 *   soldByDb = totalStock - dbStockRemaining
 *   dbStockRemaining == redisStockRemaining          -- Redis 與 DB 底線相符
 *   soldByDb == validOrderCount                       -- 售出量 == 有效訂單數
 *   dbStockRemaining == totalStock + stockLogNetDelta -- 與流水淨值相符
 * </pre>
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    private final TicketTypeMapper ticketTypeMapper;
    private final ReconcileMapper reconcileMapper;
    private final StockCache stockCache;

    public ReconcileService(TicketTypeMapper ticketTypeMapper, ReconcileMapper reconcileMapper,
                            StockCache stockCache) {
        this.ticketTypeMapper = ticketTypeMapper;
        this.reconcileMapper = reconcileMapper;
        this.stockCache = stockCache;
    }

    public ReconcileResponse reconcile(long ticketTypeId) {
        TicketType t = ticketTypeMapper.findById(ticketTypeId);
        if (t == null) {
            throw new BusinessException(BizCode.TICKET_TYPE_NOT_FOUND);
        }
        int totalStock = t.getTotalStock();
        int dbRemaining = t.getStockRemaining();
        Integer redisRemaining = stockCache.getStock(ticketTypeId);
        long validOrders = reconcileMapper.countValidOrders(ticketTypeId);
        int logNet = reconcileMapper.sumStockLogDelta(ticketTypeId);
        int soldByDb = totalStock - dbRemaining;

        boolean consistent =
                Objects.equals(redisRemaining, dbRemaining)
                        && soldByDb == validOrders
                        && dbRemaining == totalStock + logNet;

        if (!consistent) {
            log.warn("對帳不一致 ticketTypeId={} totalStock={} dbRemaining={} redisRemaining={} "
                            + "validOrders={} logNet={} soldByDb={}",
                    ticketTypeId, totalStock, dbRemaining, redisRemaining, validOrders, logNet, soldByDb);
        } else {
            log.info("對帳一致 ticketTypeId={} soldByDb={}", ticketTypeId, soldByDb);
        }

        return new ReconcileResponse(String.valueOf(ticketTypeId), totalStock, dbRemaining,
                redisRemaining, validOrders, logNet, soldByDb, consistent);
    }
}
