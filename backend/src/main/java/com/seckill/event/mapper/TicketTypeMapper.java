package com.seckill.event.mapper;

import com.seckill.event.domain.TicketType;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 票種資料存取(SQL 手寫於 TicketTypeMapper.xml,參數一律 #{})。 */
@Mapper
public interface TicketTypeMapper {

    int insert(TicketType ticketType);

    TicketType findById(@Param("id") long id);

    List<TicketType> findByEventId(@Param("eventId") long eventId);

    long countByEventId(@Param("eventId") long eventId);

    /** 更新可變欄位(含 stock_remaining 與 updated_at);僅 OFFLINE 票種由 service 允許呼叫。 */
    int update(TicketType ticketType);

    int deleteById(@Param("id") long id);

    /** 票種上線:冪等地將 status 設為 ONLINE;回傳影響行數。 */
    int markOnline(@Param("id") long id, @Param("updatedAt") Instant updatedAt);

    /**
     * 防超賣的最後一道防線(設計文件第 5 節):條件扣減 DB 庫存。
     * {@code WHERE stock_remaining > 0};回傳影響行數,0 代表扣減失敗(消費者須回補 Redis 並標記 FAIL)。
     */
    int deductStock(@Param("id") long id, @Param("updatedAt") Instant updatedAt);
}
