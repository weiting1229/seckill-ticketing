package com.seckill.admin;

import com.seckill.common.web.ApiResponse;
import com.seckill.event.dto.CreateTicketTypeRequest;
import com.seckill.event.dto.ReconcileResponse;
import com.seckill.event.dto.TicketTypeAdminResponse;
import com.seckill.event.dto.UpdateTicketTypeRequest;
import com.seckill.event.dto.WarmupResponse;
import com.seckill.event.service.ReconcileService;
import com.seckill.event.service.TicketTypeService;
import jakarta.validation.Valid;
import java.util.List;
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
 * 票種管理 API(admin),含庫存預熱與對帳。雙層防護(URL 層 + 方法層 @PreAuthorize)。
 */
@RestController
@RequestMapping("/api/v1/admin/ticket-types")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTicketTypeController {

    private final TicketTypeService ticketTypeService;
    private final ReconcileService reconcileService;

    public AdminTicketTypeController(TicketTypeService ticketTypeService,
                                     ReconcileService reconcileService) {
        this.ticketTypeService = ticketTypeService;
        this.reconcileService = reconcileService;
    }

    @PostMapping
    public ApiResponse<TicketTypeAdminResponse> create(
            @Valid @RequestBody CreateTicketTypeRequest request) {
        return ApiResponse.ok(ticketTypeService.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<TicketTypeAdminResponse> get(@PathVariable long id) {
        return ApiResponse.ok(ticketTypeService.getAdmin(id));
    }

    /** 依活動列出票種(admin)。 */
    @GetMapping
    public ApiResponse<List<TicketTypeAdminResponse>> listByEvent(@RequestParam long eventId) {
        return ApiResponse.ok(ticketTypeService.listByEvent(eventId));
    }

    @PutMapping("/{id}")
    public ApiResponse<TicketTypeAdminResponse> update(
            @PathVariable long id, @Valid @RequestBody UpdateTicketTypeRequest request) {
        return ApiResponse.ok(ticketTypeService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        ticketTypeService.delete(id);
        return ApiResponse.ok();
    }

    /** 庫存預熱:上線 + 寫入 Redis(冪等,不覆蓋已扣減庫存)。 */
    @PostMapping("/{id}/warmup")
    public ApiResponse<WarmupResponse> warmup(@PathVariable long id) {
        return ApiResponse.ok(ticketTypeService.warmup(id));
    }

    /** 對帳:DB / Redis / 有效訂單 / stock_logs 淨值四方比對。 */
    @GetMapping("/{id}/reconcile")
    public ApiResponse<ReconcileResponse> reconcile(@PathVariable long id) {
        return ApiResponse.ok(reconcileService.reconcile(id));
    }
}
