package com.seckill.order.mapper;

import com.seckill.order.domain.Order;
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
}
