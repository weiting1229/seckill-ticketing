package com.seckill.event.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 對帳專用查詢:讀取 orders 與 stock_logs 以與票種庫存比對。
 *
 * <p>orders / stock_logs 表在 V1 已建立;其寫入邏輯於 M3/M4 實作,此處僅唯讀彙總,
 * 故 M2 對帳一個未售出的票種時,兩者皆回 0(與初始庫存三方一致)。
 */
@Mapper
public interface ReconcileMapper {

    /** 有效訂單數:PAID + PENDING_PAYMENT(取消/過期不計)。 */
    long countValidOrders(@Param("ticketTypeId") long ticketTypeId);

    /** stock_logs 淨值 SUM(delta);無流水時回 0。扣減 -1、回補 +1。 */
    int sumStockLogDelta(@Param("ticketTypeId") long ticketTypeId);
}
