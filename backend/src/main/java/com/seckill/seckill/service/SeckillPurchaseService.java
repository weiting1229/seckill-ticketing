package com.seckill.seckill.service;

import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.IdGenerator;
import com.seckill.event.service.StockCache;
import com.seckill.order.mq.OrderMessage;
import com.seckill.order.mq.OrderMessagePublisher;
import com.seckill.order.service.OrderResultCache;
import com.seckill.seckill.dto.PurchaseResponse;
import com.seckill.seckill.dto.SeckillResultResponse;
import com.seckill.seckill.metrics.SeckillMetrics;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 搶購核心流程(設計文件第 2、6、10 節)。限流已由攔截器前置完成;此處<b>全程只碰 Redis 與 MQ,
 * 絕不觸 DB</b>:token 校驗(Lua)→ deduct(Lua)→ 產 orderId → 發 MQ(失敗則 revert 回補)。
 *
 * <p>各拒絕路徑回對應 3xxx 並累加 {@code seckill_requests_total} 對應 result 標籤;落庫為非同步,
 * 前端以 requestId 輪詢 {@link #getResult}。
 */
@Service
public class SeckillPurchaseService {

    private static final Logger log = LoggerFactory.getLogger(SeckillPurchaseService.class);

    private final SeckillTokenService tokenService;
    private final StockCache stockCache;
    private final IdGenerator idGenerator;
    private final OrderMessagePublisher publisher;
    private final OrderResultCache resultCache;
    private final SeckillMetrics metrics;

    public SeckillPurchaseService(SeckillTokenService tokenService, StockCache stockCache,
                                  IdGenerator idGenerator, OrderMessagePublisher publisher,
                                  OrderResultCache resultCache, SeckillMetrics metrics) {
        this.tokenService = tokenService;
        this.stockCache = stockCache;
        this.idGenerator = idGenerator;
        this.publisher = publisher;
        this.resultCache = resultCache;
        this.metrics = metrics;
    }

    public PurchaseResponse purchase(long userId, long ticketTypeId, String token) {
        String tt = String.valueOf(ticketTypeId);

        // 1) 一次性 token 原子校驗 + 焚毀
        if (!tokenService.consume(userId, ticketTypeId, token)) {
            metrics.incrementRequest("invalid_token", tt);
            throw new BusinessException(BizCode.SECKILL_INVALID_TOKEN);
        }

        // 2) Redis 原子扣減(查重複 → 查庫存 → 扣 → 記已購)
        long result = stockCache.deduct(ticketTypeId, userId);
        if (result == StockCache.DEDUCT_DUPLICATE) {
            metrics.incrementRequest("duplicate", tt);
            throw new BusinessException(BizCode.SECKILL_DUPLICATE_PURCHASE);
        }
        if (result == StockCache.DEDUCT_SOLD_OUT) {
            metrics.incrementRequest("sold_out", tt);
            throw new BusinessException(BizCode.SECKILL_SOLD_OUT);
        }
        if (result == StockCache.DEDUCT_NOT_WARMED) {
            metrics.incrementRequest("not_warmed", tt);
            throw new BusinessException(BizCode.SECKILL_NOT_WARMED);
        }

        // 3) 扣減成功:產 orderId + requestId,發 MQ 削峰
        long orderId = idGenerator.nextId();
        String requestId = UUID.randomUUID().toString();
        OrderMessage message = new OrderMessage(
                requestId, userId, ticketTypeId, orderId, System.currentTimeMillis());

        if (!publisher.publish(message)) {
            // 發送未確認:回補 Redis 庫存與已購,回錯(絕不留下扣了庫存卻無訂單的狀態)
            stockCache.revert(ticketTypeId, userId);
            metrics.incrementRequest("enqueue_failed", tt);
            log.error("MQ 發送失敗,已回補 Redis requestId={} ticketTypeId={}", requestId, ticketTypeId);
            throw new BusinessException(BizCode.SECKILL_ENQUEUE_FAILED);
        }

        metrics.incrementRequest("success", tt);
        return new PurchaseResponse(requestId, String.valueOf(orderId));
    }

    /** 輪詢排隊結果:讀 {@code seckill:result:{requestId}},無 key = QUEUING。 */
    public SeckillResultResponse getResult(String requestId) {
        String value = resultCache.get(requestId);
        if (value == null) {
            return SeckillResultResponse.queuing();
        }
        if (value.startsWith(OrderResultCache.SUCCESS_PREFIX)) {
            return SeckillResultResponse.success(value.substring(OrderResultCache.SUCCESS_PREFIX.length()));
        }
        if (value.startsWith(OrderResultCache.FAIL_PREFIX)) {
            return SeckillResultResponse.fail(value.substring(OrderResultCache.FAIL_PREFIX.length()));
        }
        return SeckillResultResponse.queuing();
    }
}
