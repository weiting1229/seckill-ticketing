package com.seckill.event.mapper;

import com.seckill.event.domain.Event;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 活動資料存取(SQL 手寫於 EventMapper.xml,參數一律 #{})。 */
@Mapper
public interface EventMapper {

    int insert(Event event);

    Event findById(@Param("id") long id);

    /** 更新可變欄位與狀態(含 updated_at);回傳影響行數。 */
    int update(Event event);

    int deleteById(@Param("id") long id);

    // --- 公開:僅 PUBLISHED,依演出時間排序,分頁 ---
    List<Event> findPublishedPage(@Param("limit") int limit, @Param("offset") int offset);

    long countPublished();

    // --- admin:全部狀態,依建立時間新到舊,分頁 ---
    List<Event> findPage(@Param("limit") int limit, @Param("offset") int offset);

    long countAll();
}
