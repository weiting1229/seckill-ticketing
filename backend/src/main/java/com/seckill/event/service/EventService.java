package com.seckill.event.service;

import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.IdGenerator;
import com.seckill.event.domain.Event;
import com.seckill.event.domain.EventStatus;
import com.seckill.event.domain.TicketType;
import com.seckill.event.dto.CreateEventRequest;
import com.seckill.event.dto.EventAdminResponse;
import com.seckill.event.dto.EventDetailResponse;
import com.seckill.event.dto.EventSummaryResponse;
import com.seckill.event.dto.PageResponse;
import com.seckill.event.dto.TicketTypeView;
import com.seckill.event.dto.UpdateEventRequest;
import com.seckill.event.mapper.EventMapper;
import com.seckill.event.mapper.TicketTypeMapper;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 活動領域服務:admin CRUD 與公開查詢(列表 / 詳情)。
 * admin 操作寫結構化稽核日誌(設計文件第 10 節第 8 點)。
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventMapper eventMapper;
    private final TicketTypeMapper ticketTypeMapper;
    private final StockCache stockCache;
    private final IdGenerator idGenerator;

    public EventService(EventMapper eventMapper, TicketTypeMapper ticketTypeMapper,
                        StockCache stockCache, IdGenerator idGenerator) {
        this.eventMapper = eventMapper;
        this.ticketTypeMapper = ticketTypeMapper;
        this.stockCache = stockCache;
        this.idGenerator = idGenerator;
    }

    // ---- admin ----

    @Transactional
    public EventAdminResponse create(CreateEventRequest req) {
        Instant now = Instant.now();
        Event event = new Event();
        event.setId(idGenerator.nextId());
        event.setTitle(req.title());
        event.setDescription(req.description());
        event.setVenue(req.venue());
        event.setEventTime(req.eventTime());
        event.setStatus(EventStatus.DRAFT);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        eventMapper.insert(event);
        log.info("admin 建立活動 eventId={} title={}", event.getId(), req.title());
        return EventAdminResponse.from(event);
    }

    public EventAdminResponse getAdmin(long id) {
        return EventAdminResponse.from(requireEvent(id));
    }

    public PageResponse<EventAdminResponse> listAdmin(int page, int size) {
        int p = normalizePage(page);
        int s = normalizeSize(size);
        long total = eventMapper.countAll();
        List<EventAdminResponse> items = eventMapper.findPage(s, (p - 1) * s).stream()
                .map(EventAdminResponse::from)
                .toList();
        return PageResponse.of(p, s, total, items);
    }

    @Transactional
    public EventAdminResponse update(long id, UpdateEventRequest req) {
        Event event = requireEvent(id);
        if (!event.getStatus().canTransitionTo(req.status())) {
            throw new BusinessException(BizCode.EVENT_STATUS_INVALID,
                    "活動狀態不可由 " + event.getStatus() + " 轉為 " + req.status());
        }
        event.setTitle(req.title());
        event.setDescription(req.description());
        event.setVenue(req.venue());
        event.setEventTime(req.eventTime());
        event.setStatus(req.status());
        event.setUpdatedAt(Instant.now());
        eventMapper.update(event);
        log.info("admin 更新活動 eventId={} status={}", id, req.status());
        return EventAdminResponse.from(event);
    }

    @Transactional
    public void delete(long id) {
        requireEvent(id);
        if (ticketTypeMapper.countByEventId(id) > 0) {
            throw new BusinessException(BizCode.EVENT_DELETE_FORBIDDEN);
        }
        eventMapper.deleteById(id);
        log.info("admin 刪除活動 eventId={}", id);
    }

    // ---- 公開 ----

    public PageResponse<EventSummaryResponse> listPublished(int page, int size) {
        int p = normalizePage(page);
        int s = normalizeSize(size);
        long total = eventMapper.countPublished();
        List<EventSummaryResponse> items = eventMapper.findPublishedPage(s, (p - 1) * s).stream()
                .map(EventSummaryResponse::from)
                .toList();
        return PageResponse.of(p, s, total, items);
    }

    /** 公開活動詳情:僅 PUBLISHED 可見(未發布視同不存在,避免洩漏草稿)。 */
    public EventDetailResponse getPublishedDetail(long id) {
        Event event = eventMapper.findById(id);
        if (event == null || event.getStatus() != EventStatus.PUBLISHED) {
            throw new BusinessException(BizCode.EVENT_NOT_FOUND);
        }
        List<TicketType> ticketTypes = ticketTypeMapper.findByEventId(id);
        List<TicketTypeView> views = ticketTypes.stream()
                .map(t -> TicketTypeView.of(t, stockCache.getStock(t.getId())))
                .toList();
        return EventDetailResponse.of(event, views, Instant.now());
    }

    private Event requireEvent(long id) {
        Event event = eventMapper.findById(id);
        if (event == null) {
            throw new BusinessException(BizCode.EVENT_NOT_FOUND);
        }
        return event;
    }

    private int normalizePage(int page) {
        return Math.max(page, 1);
    }

    private int normalizeSize(int size) {
        return Math.min(Math.max(size, 1), 50);
    }
}
