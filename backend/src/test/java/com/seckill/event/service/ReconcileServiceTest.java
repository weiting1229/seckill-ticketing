package com.seckill.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.seckill.common.exception.BusinessException;
import com.seckill.event.domain.TicketType;
import com.seckill.event.domain.TicketTypeStatus;
import com.seckill.event.dto.ReconcileResponse;
import com.seckill.event.mapper.ReconcileMapper;
import com.seckill.event.mapper.TicketTypeMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 對帳一致性判定的純邏輯測試(不依賴容器)。 */
@ExtendWith(MockitoExtension.class)
class ReconcileServiceTest {

    @Mock TicketTypeMapper ticketTypeMapper;
    @Mock ReconcileMapper reconcileMapper;
    @Mock StockCache stockCache;

    @InjectMocks ReconcileService reconcileService;

    private static final long TT_ID = 5001L;

    private TicketType ticketType(int total, int remaining) {
        TicketType t = new TicketType();
        t.setId(TT_ID);
        t.setEventId(1L);
        t.setName("A");
        t.setPrice(new BigDecimal("100.00"));
        t.setTotalStock(total);
        t.setStockRemaining(remaining);
        t.setStatus(TicketTypeStatus.ONLINE);
        return t;
    }

    @Test
    void allFourSidesAlignedIsConsistent() {
        when(ticketTypeMapper.findById(TT_ID)).thenReturn(ticketType(1000, 990));
        when(stockCache.getStock(TT_ID)).thenReturn(990);
        when(reconcileMapper.countValidOrders(TT_ID)).thenReturn(10L);
        when(reconcileMapper.sumStockLogDelta(TT_ID)).thenReturn(-10);

        ReconcileResponse r = reconcileService.reconcile(TT_ID);

        assertThat(r.consistent()).isTrue();
        assertThat(r.soldByDb()).isEqualTo(10);
        assertThat(r.validOrderCount()).isEqualTo(10);
    }

    @Test
    void redisNullIsInconsistent() {
        when(ticketTypeMapper.findById(TT_ID)).thenReturn(ticketType(1000, 1000));
        when(stockCache.getStock(TT_ID)).thenReturn(null);
        when(reconcileMapper.countValidOrders(TT_ID)).thenReturn(0L);
        when(reconcileMapper.sumStockLogDelta(TT_ID)).thenReturn(0);

        assertThat(reconcileService.reconcile(TT_ID).consistent()).isFalse();
    }

    @Test
    void orderCountMismatchIsInconsistent() {
        when(ticketTypeMapper.findById(TT_ID)).thenReturn(ticketType(1000, 990));
        when(stockCache.getStock(TT_ID)).thenReturn(990);
        when(reconcileMapper.countValidOrders(TT_ID)).thenReturn(9L); // 應為 10
        when(reconcileMapper.sumStockLogDelta(TT_ID)).thenReturn(-10);

        assertThat(reconcileService.reconcile(TT_ID).consistent()).isFalse();
    }

    @Test
    void stockLogNetMismatchIsInconsistent() {
        when(ticketTypeMapper.findById(TT_ID)).thenReturn(ticketType(1000, 990));
        when(stockCache.getStock(TT_ID)).thenReturn(990);
        when(reconcileMapper.countValidOrders(TT_ID)).thenReturn(10L);
        when(reconcileMapper.sumStockLogDelta(TT_ID)).thenReturn(-9); // 應為 -10

        assertThat(reconcileService.reconcile(TT_ID).consistent()).isFalse();
    }

    @Test
    void missingTicketTypeThrows2004() {
        when(ticketTypeMapper.findById(TT_ID)).thenReturn(null);
        assertThatThrownBy(() -> reconcileService.reconcile(TT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(2004));
    }
}
