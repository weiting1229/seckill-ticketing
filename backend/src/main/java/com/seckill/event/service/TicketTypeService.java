package com.seckill.event.service;

import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.IdGenerator;
import com.seckill.event.domain.Event;
import com.seckill.event.domain.TicketType;
import com.seckill.event.domain.TicketTypeStatus;
import com.seckill.event.dto.CreateTicketTypeRequest;
import com.seckill.event.dto.TicketTypeAdminResponse;
import com.seckill.event.dto.UpdateTicketTypeRequest;
import com.seckill.event.dto.WarmupResponse;
import com.seckill.event.mapper.EventMapper;
import com.seckill.event.mapper.TicketTypeMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 票種領域服務:admin CRUD 與庫存預熱(warmup)。
 *
 * <p>編輯限制:僅 OFFLINE 票種可修改/刪除;ONLINE(已預熱)票種回 2005,
 * 避免破壞 Redis 中已扣減的庫存與 DB 底線的一致性。
 */
@Service
public class TicketTypeService {

    private static final Logger log = LoggerFactory.getLogger(TicketTypeService.class);

    /** Redis 庫存 TTL:活動結束(以演出時間計)+ 1 天(設計文件第 6 節)。 */
    private static final Duration STOCK_TTL_AFTER_EVENT = Duration.ofDays(1);
    /** 保底 TTL,避免對(誤設為)已過期活動預熱時算出非正數 TTL。 */
    private static final long MIN_TTL_SECONDS = 3600;

    private final TicketTypeMapper ticketTypeMapper;
    private final EventMapper eventMapper;
    private final StockCache stockCache;
    private final IdGenerator idGenerator;

    public TicketTypeService(TicketTypeMapper ticketTypeMapper, EventMapper eventMapper,
                             StockCache stockCache, IdGenerator idGenerator) {
        this.ticketTypeMapper = ticketTypeMapper;
        this.eventMapper = eventMapper;
        this.stockCache = stockCache;
        this.idGenerator = idGenerator;
    }

    @Transactional
    public TicketTypeAdminResponse create(CreateTicketTypeRequest req) {
        requireEvent(req.eventId());
        if (!req.seckillStart().isBefore(req.seckillEnd())) {
            throw new BusinessException(BizCode.TICKET_TIME_RANGE_INVALID);
        }
        Instant now = Instant.now();
        TicketType t = new TicketType();
        t.setId(idGenerator.nextId());
        t.setEventId(req.eventId());
        t.setName(req.name());
        t.setPrice(req.price());
        t.setTotalStock(req.totalStock());
        t.setStockRemaining(req.totalStock()); // 尚未售出,剩餘 = 總量
        t.setSeckillStart(req.seckillStart());
        t.setSeckillEnd(req.seckillEnd());
        t.setStatus(TicketTypeStatus.OFFLINE);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        ticketTypeMapper.insert(t);
        log.info("admin 建立票種 ticketTypeId={} eventId={} totalStock={}",
                t.getId(), req.eventId(), req.totalStock());
        return TicketTypeAdminResponse.from(t);
    }

    public TicketTypeAdminResponse getAdmin(long id) {
        return TicketTypeAdminResponse.from(requireTicketType(id));
    }

    public List<TicketTypeAdminResponse> listByEvent(long eventId) {
        return ticketTypeMapper.findByEventId(eventId).stream()
                .map(TicketTypeAdminResponse::from)
                .toList();
    }

    @Transactional
    public TicketTypeAdminResponse update(long id, UpdateTicketTypeRequest req) {
        TicketType t = requireTicketType(id);
        requireOffline(t);
        if (!req.seckillStart().isBefore(req.seckillEnd())) {
            throw new BusinessException(BizCode.TICKET_TIME_RANGE_INVALID);
        }
        t.setName(req.name());
        t.setPrice(req.price());
        t.setTotalStock(req.totalStock());
        t.setStockRemaining(req.totalStock()); // OFFLINE 未預熱,無已扣減庫存,可同步重設
        t.setSeckillStart(req.seckillStart());
        t.setSeckillEnd(req.seckillEnd());
        t.setUpdatedAt(Instant.now());
        ticketTypeMapper.update(t);
        log.info("admin 更新票種 ticketTypeId={}", id);
        return TicketTypeAdminResponse.from(t);
    }

    @Transactional
    public void delete(long id) {
        TicketType t = requireTicketType(id);
        requireOffline(t);
        ticketTypeMapper.deleteById(id);
        log.info("admin 刪除票種 ticketTypeId={}", id);
    }

    /**
     * 庫存預熱:票種上線(status=ONLINE)+ 將 DB stock_remaining 寫入 Redis。
     *
     * <p>冪等保證:重複呼叫不會覆蓋 Redis 現值(Lua 條件寫入),故熱路徑已扣減的庫存不被蓋回。
     * {@code alreadyWarmed} 以票種是否已 ONLINE 判定,對外呈現冪等語意。
     */
    @Transactional
    public WarmupResponse warmup(long id) {
        TicketType t = requireTicketType(id);
        Event event = requireEvent(t.getEventId());
        boolean alreadyWarmed = t.getStatus() == TicketTypeStatus.ONLINE;

        Instant now = Instant.now();
        if (!alreadyWarmed) {
            ticketTypeMapper.markOnline(id, now);
        }
        long ttlSeconds = computeTtlSeconds(event.getEventTime(), now);
        long redisStock = stockCache.warmup(id, t.getStockRemaining(), ttlSeconds);

        log.info("admin 預熱票種 ticketTypeId={} alreadyWarmed={} dbStock={} redisStock={} ttlSec={}",
                id, alreadyWarmed, t.getStockRemaining(), redisStock, ttlSeconds);
        return new WarmupResponse(String.valueOf(id), TicketTypeStatus.ONLINE.name(),
                t.getStockRemaining(), (int) redisStock, alreadyWarmed);
    }

    private long computeTtlSeconds(Instant eventTime, Instant now) {
        long seconds = Duration.between(now, eventTime.plus(STOCK_TTL_AFTER_EVENT)).getSeconds();
        return Math.max(seconds, MIN_TTL_SECONDS);
    }

    private Event requireEvent(long eventId) {
        Event event = eventMapper.findById(eventId);
        if (event == null) {
            throw new BusinessException(BizCode.EVENT_NOT_FOUND);
        }
        return event;
    }

    private TicketType requireTicketType(long id) {
        TicketType t = ticketTypeMapper.findById(id);
        if (t == null) {
            throw new BusinessException(BizCode.TICKET_TYPE_NOT_FOUND);
        }
        return t;
    }

    private void requireOffline(TicketType t) {
        if (t.getStatus() != TicketTypeStatus.OFFLINE) {
            throw new BusinessException(BizCode.TICKET_TYPE_NOT_EDITABLE);
        }
    }
}
