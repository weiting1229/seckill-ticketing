package com.seckill.order.service;

import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.event.dto.PageResponse;
import com.seckill.order.domain.Order;
import com.seckill.order.dto.OrderResponse;
import com.seckill.order.mapper.OrderMapper;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 訂單查詢與支付服務(設計文件第 9 節)。狀態轉移一律落實於 SQL 層條件 UPDATE(CLAUDE.md)。
 *
 * <p>分頁沿用 {@link PageResponse}(order 模組本就依賴 event,復用其通用分頁型別;
 * 未來若上移至 common 再一併調整,見 M4 總結)。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderMapper orderMapper;

    public OrderService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /** 我的訂單列表,分頁依 created_at DESC。page/size 於此 clamp(page≥1、size 1–50),不拋校驗例外。 */
    public PageResponse<OrderResponse> listMyOrders(long userId, int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 50);
        long total = orderMapper.countByUser(userId);
        List<OrderResponse> items = orderMapper.findPageByUser(userId, s, (p - 1) * s).stream()
                .map(OrderResponse::from)
                .toList();
        return PageResponse.of(p, s, total, items);
    }

    /** 我的訂單詳情:校驗歸屬,他人訂單一律回 404(不洩漏存在性,設計文件第 9 節)。 */
    public OrderResponse getMyOrder(long userId, long orderId) {
        Order order = orderMapper.findById(orderId);
        if (order == null || order.getUserId() != userId) {
            throw new BusinessException(BizCode.ORDER_NOT_FOUND);
        }
        return OrderResponse.from(order);
    }

    /**
     * 模擬支付:條件 UPDATE 僅本人且 PENDING_PAYMENT 可轉 PAID(狀態機)。影響行數 0 時區分:
     * <ul>
     *   <li>訂單不存在或<b>非本人</b> → {@link BizCode#ORDER_NOT_FOUND}(404,他人訂單一律回 404,
     *       不洩漏訂單存在性)。</li>
     *   <li>本人但狀態非 PENDING_PAYMENT(已支付 / 已取消 / 已逾時)→ {@link BizCode#ORDER_STATUS_INVALID}
     *       (409,非法轉移)。</li>
     * </ul>
     * 與超時取消的併發競態靠此條件 UPDATE 天然互斥(恰一方成功)。
     */
    public OrderResponse pay(long userId, long orderId) {
        Instant now = Instant.now();
        int affected = orderMapper.payIfPending(orderId, userId, now, now);
        if (affected == 0) {
            Order order = orderMapper.findById(orderId);
            if (order == null || order.getUserId() != userId) {
                throw new BusinessException(BizCode.ORDER_NOT_FOUND);
            }
            throw new BusinessException(BizCode.ORDER_STATUS_INVALID,
                    "訂單狀態為 " + order.getStatus() + ",無法支付");
        }
        log.info("訂單支付成功 orderId={} userId={}", orderId, userId);
        return OrderResponse.from(orderMapper.findById(orderId));
    }
}
