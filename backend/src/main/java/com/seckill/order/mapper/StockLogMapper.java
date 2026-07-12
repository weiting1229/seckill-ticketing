package com.seckill.order.mapper;

import com.seckill.order.domain.StockLog;
import org.apache.ibatis.annotations.Mapper;

/** 庫存流水資料存取(SQL 手寫於 StockLogMapper.xml)。 */
@Mapper
public interface StockLogMapper {

    int insert(StockLog stockLog);
}
