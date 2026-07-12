package com.seckill.order.mapper;

import com.seckill.order.domain.Order;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 訂單資料存取(SQL 手寫於 OrderMapper.xml,參數一律 #{})。 */
@Mapper
public interface OrderMapper {

    /**
     * 建立訂單。違反 {@code uq_orders_request} 或 {@code uq_orders_user_ticket} 時拋
     * {@code DuplicateKeyException}(由 MyBatis-Spring 例外轉譯),消費者據此判定冪等。
     */
    int insert(Order order);

    /** 依冪等鍵查訂單;供輪詢結果與測試使用。 */
    Order findByRequestId(@Param("requestId") String requestId);

    /** 依主鍵查訂單;取消回補需其 ticketTypeId / userId,支付與詳情查詢亦用。 */
    Order findById(@Param("id") long id);

    /**
     * 超時取消(狀態機,設計文件第 9 節):條件 UPDATE 僅在 PENDING_PAYMENT 時轉 EXPIRED,
     * {@code paid_at} 不動。回傳影響行數;0 代表已 PAID / 已取消 / 不存在(no-op)。
     * 取消與支付對同一訂單的併發競態靠此條件 UPDATE 天然互斥。
     */
    int expireIfPending(@Param("id") long id, @Param("updatedAt") Instant updatedAt);

    /**
     * 模擬支付(狀態機,設計文件第 9 節):條件 UPDATE 僅在本人且 PENDING_PAYMENT 時轉 PAID。
     * 回傳影響行數;0 代表不存在 / 非本人 / 狀態非法。支付與超時取消對同一訂單的競態靠此天然互斥。
     */
    int payIfPending(@Param("id") long id, @Param("userId") long userId,
                     @Param("paidAt") Instant paidAt, @Param("updatedAt") Instant updatedAt);

    /** 我的訂單分頁,依 created_at DESC(走 idx_orders_user);id 為次序穩定的 tiebreaker。 */
    List<Order> findPageByUser(@Param("userId") long userId,
                               @Param("limit") int limit, @Param("offset") int offset);

    /** 我的訂單總數(分頁 total)。 */
    long countByUser(@Param("userId") long userId);

    /**
     * 兜底排程用:掃出逾時未支付訂單 id(走部分索引 idx_orders_status_expire)。
     * 依 expire_at ASC 取最舊逾時者、限批次上限,交由 OrderCancelService 逐筆冪等取消回補。
     */
    List<Long> findExpiredPendingIds(@Param("now") Instant now, @Param("limit") int limit);
}
