package com.seckill.event;

import com.seckill.common.web.ApiResponse;
import com.seckill.event.dto.EventDetailResponse;
import com.seckill.event.dto.EventSummaryResponse;
import com.seckill.event.dto.PageResponse;
import com.seckill.event.service.EventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公開活動 API(設計文件第 9 節):匿名可存取(於 SecurityConfig 明列 GET 白名單)。
 * 分頁參數在 service 層 clamp(page≥1、size 1–50),不拋校驗例外。
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public ApiResponse<PageResponse<EventSummaryResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(eventService.listPublished(page, size, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<EventDetailResponse> detail(@PathVariable long id) {
        return ApiResponse.ok(eventService.getPublishedDetail(id));
    }
}
