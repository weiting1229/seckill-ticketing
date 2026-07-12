package com.seckill.order.service;

import com.seckill.common.id.IdGenerator;
import com.seckill.event.domain.TicketType;
import com.seckill.event.mapper.TicketTypeMapper;
import com.seckill.order.domain.Order;
import com.seckill.order.domain.OrderStatus;
import com.seckill.order.domain.StockLog;
import com.seckill.order.domain.StockLogType;
import com.seckill.order.mapper.OrderMapper;
import com.seckill.order.mapper.StockLogMapper;
import com.seckill.order.mq.OrderMessage;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 建單落庫(設計文件第 5、7 節)。單一事務內完成:建訂單 + 條件扣 DB 庫存 + 寫 DEDUCT 流水,
 * 三者原子(任一失敗全回滾)。
 *
 * <p>執行順序刻意為<b>先建訂單、再扣庫存</b>:
 * <ul>
 *   <li>重複訊息(冪等):建訂單即撞 {@code uq_orders_request},拋 DuplicateKeyException,
 *       在扣庫存前就中止,不會誤觸「售罄」補償。</li>
 *   <li>DB 售罄(異常訊號):扣庫存影響行數 0 → 拋 {@link DbStockDepletedException} → 整筆回滾
 *       (含剛建立的訂單)。</li>
 * </ul>
 */
@Service
public class OrderCreateService {

    /** 支付截止:建單後 15 分鐘(設計文件第 2、7 節;逾時取消屬 M4)。 */
    static final Duration PAYMENT_WINDOW = Duration.ofMinutes(15);

    private final TicketTypeMapper ticketTypeMapper;
    private final OrderMapper orderMapper;
    private final StockLogMapper stockLogMapper;
    private final IdGenerator idGenerator;

    public OrderCreateService(TicketTypeMapper ticketTypeMapper, OrderMapper orderMapper,
                              StockLogMapper stockLogMapper, IdGenerator idGenerator) {
        this.ticketTypeMapper = ticketTypeMapper;
        this.orderMapper = orderMapper;
        this.stockLogMapper = stockLogMapper;
        this.idGenerator = idGenerator;
    }

    @Transactional
    public void createOrder(OrderMessage message) {
        TicketType ticketType = ticketTypeMapper.findById(message.ticketTypeId());
        if (ticketType == null) {
            // 不應發生(熱路徑已預熱該票種);視為未預期例外 → 交由消費者重試 / DLQ
            throw new IllegalStateException("建單時票種不存在 ticketTypeId=" + message.ticketTypeId());
        }
        Instant now = Instant.now();

        Order order = new Order();
        order.setId(message.orderId());
        order.setUserId(message.userId());
        order.setEventId(ticketType.getEventId());
        order.setTicketTypeId(message.ticketTypeId());
        order.setPrice(ticketType.getPrice());
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setRequestId(message.requestId());
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order.setExpireAt(now.plus(PAYMENT_WINDOW));
        orderMapper.insert(order); // 冪等:唯一鍵衝突 → DuplicateKeyException

        int affected = ticketTypeMapper.deductStock(message.ticketTypeId(), now);
        if (affected == 0) {
            throw new DbStockDepletedException(message.ticketTypeId());
        }

        StockLog log = new StockLog();
        log.setId(idGenerator.nextId());
        log.setTicketTypeId(message.ticketTypeId());
        log.setOrderId(message.orderId());
        log.setDelta(-1);
        log.setType(StockLogType.DEDUCT);
        log.setCreatedAt(now);
        stockLogMapper.insert(log);
    }
}
