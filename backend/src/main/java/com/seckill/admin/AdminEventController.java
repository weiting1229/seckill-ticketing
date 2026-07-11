package com.seckill.admin;

import com.seckill.common.web.ApiResponse;
import com.seckill.event.dto.CreateEventRequest;
import com.seckill.event.dto.EventAdminResponse;
import com.seckill.event.dto.PageResponse;
import com.seckill.event.dto.UpdateEventRequest;
import com.seckill.event.service.EventService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活動管理 API(admin)。雙層防護:URL 層於 SecurityConfig 限定 {@code /api/v1/admin/**} 需 ADMIN,
 * 方法層再加 {@code @PreAuthorize}(CLAUDE.md 要求)。例外一律拋出交全域處理器。
 */
@RestController
@RequestMapping("/api/v1/admin/events")
@PreAuthorize("hasRole('ADMIN')")
public class AdminEventController {

    private final EventService eventService;

    public AdminEventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ApiResponse<EventAdminResponse> create(@Valid @RequestBody CreateEventRequest request) {
        return ApiResponse.ok(eventService.create(request));
    }

    @GetMapping
    public ApiResponse<PageResponse<EventAdminResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(eventService.listAdmin(page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<EventAdminResponse> get(@PathVariable long id) {
        return ApiResponse.ok(eventService.getAdmin(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<EventAdminResponse> update(
            @PathVariable long id, @Valid @RequestBody UpdateEventRequest request) {
        return ApiResponse.ok(eventService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        eventService.delete(id);
        return ApiResponse.ok();
    }
}
