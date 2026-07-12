package com.seckill.order;

import com.seckill.auth.security.AuthUser;
import com.seckill.common.web.ApiResponse;
import com.seckill.event.dto.PageResponse;
import com.seckill.order.dto.OrderResponse;
import com.seckill.order.service.OrderService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 訂單 API(設計文件第 9 節)。/orders/** 於 SecurityConfig 落在預設 authenticated(USER 即可);
 * 歸屬校驗於 service 層(他人訂單回 404,不洩漏存在性)。例外一律拋出由全域處理器轉統一格式。
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** 我的訂單列表(分頁,依 created_at DESC)。 */
    @GetMapping
    public ApiResponse<PageResponse<OrderResponse>> list(
            @AuthenticationPrincipal AuthUser principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(orderService.listMyOrders(principal.userId(), page, size));
    }

    /** 我的訂單詳情(校驗歸屬;他人訂單回 404,不洩漏存在性)。 */
    @GetMapping("/{id}")
    public ApiResponse<OrderResponse> detail(
            @AuthenticationPrincipal AuthUser principal,
            @PathVariable long id) {
        return ApiResponse.ok(orderService.getMyOrder(principal.userId(), id));
    }

    /** 模擬支付(狀態機:僅本人 PENDING_PAYMENT 可轉 PAID);不存在 / 非本人回 4001,狀態非法回 4002。 */
    @PostMapping("/{id}/pay")
    public ApiResponse<OrderResponse> pay(
            @AuthenticationPrincipal AuthUser principal,
            @PathVariable long id) {
        return ApiResponse.ok(orderService.pay(principal.userId(), id));
    }
}
