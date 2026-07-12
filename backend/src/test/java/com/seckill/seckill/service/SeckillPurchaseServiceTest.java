package com.seckill.seckill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.IdGenerator;
import com.seckill.common.metrics.SeckillMetrics;
import com.seckill.event.service.StockCache;
import com.seckill.order.mq.OrderMessage;
import com.seckill.order.mq.OrderMessagePublisher;
import com.seckill.order.service.OrderResultCache;
import org.junit.jupiter.api.Test;

/**
 * 搶購核心的分支單元測試(以 mock 涵蓋難以在 e2e 觸發的補償/拒絕路徑)。
 */
class SeckillPurchaseServiceTest {

    private final SeckillTokenService tokenService = mock(SeckillTokenService.class);
    private final StockCache stockCache = mock(StockCache.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final OrderMessagePublisher publisher = mock(OrderMessagePublisher.class);
    private final OrderResultCache resultCache = mock(OrderResultCache.class);
    private final SeckillMetrics metrics = mock(SeckillMetrics.class);

    private final SeckillPurchaseService service = new SeckillPurchaseService(
            tokenService, stockCache, idGenerator, publisher, resultCache, metrics);

    private static final long USER = 1L;
    private static final long TT = 2L;
    private static final String TOKEN = "tok";

    @Test
    void enqueueFailureRevertsStockAndThrows() {
        when(tokenService.consume(USER, TT, TOKEN)).thenReturn(true);
        when(stockCache.deduct(TT, USER)).thenReturn(StockCache.DEDUCT_SUCCESS);
        when(idGenerator.nextId()).thenReturn(999L);
        when(publisher.publish(any(OrderMessage.class))).thenReturn(false); // MQ 發送未確認

        assertThatThrownBy(() -> service.purchase(USER, TT, TOKEN))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(3009);

        // 補償:回補 Redis + 計數,且不寫任何結果 key(仍為 QUEUING 由前端輪詢逾時)
        verify(stockCache).revert(TT, USER);
        verify(metrics).recordStockRevert("2");
        verify(metrics).incrementRequest("enqueue_failed", "2");
    }

    @Test
    void invalidTokenRejectedWithoutDeduct() {
        when(tokenService.consume(USER, TT, TOKEN)).thenReturn(false);

        assertThatThrownBy(() -> service.purchase(USER, TT, TOKEN))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(3007);

        verify(stockCache, never()).deduct(anyLong(), anyLong());
        verify(metrics).incrementRequest("invalid_token", "2");
    }

    @Test
    void notWarmedRejected() {
        when(tokenService.consume(USER, TT, TOKEN)).thenReturn(true);
        when(stockCache.deduct(TT, USER)).thenReturn(StockCache.DEDUCT_NOT_WARMED);

        assertThatThrownBy(() -> service.purchase(USER, TT, TOKEN))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(3008);

        verify(publisher, never()).publish(any());
        verify(metrics).incrementRequest("not_warmed", "2");
    }

    @Test
    void successReturnsRequestAndOrderId() {
        when(tokenService.consume(USER, TT, TOKEN)).thenReturn(true);
        when(stockCache.deduct(TT, USER)).thenReturn(StockCache.DEDUCT_SUCCESS);
        when(idGenerator.nextId()).thenReturn(12345L);
        when(publisher.publish(any(OrderMessage.class))).thenReturn(true);

        var resp = service.purchase(USER, TT, TOKEN);

        assertThat(resp.orderId()).isEqualTo("12345");
        assertThat(resp.requestId()).isNotBlank();
        verify(metrics).incrementRequest("success", "2");
        verify(stockCache, never()).revert(anyLong(), anyLong());
    }

    @Test
    void resultParsesSuccessFailAndQueuing() {
        when(resultCache.get("r1")).thenReturn(null);
        when(resultCache.get("r2")).thenReturn(OrderResultCache.SUCCESS_PREFIX + "555");
        when(resultCache.get("r3")).thenReturn(OrderResultCache.FAIL_PREFIX + "STOCK_DEPLETED");

        assertThat(service.getResult("r1").status()).isEqualTo("QUEUING");
        assertThat(service.getResult("r2").status()).isEqualTo("SUCCESS");
        assertThat(service.getResult("r2").orderId()).isEqualTo("555");
        assertThat(service.getResult("r3").status()).isEqualTo("FAIL");
        assertThat(service.getResult("r3").reason()).isEqualTo("STOCK_DEPLETED");
    }
}
