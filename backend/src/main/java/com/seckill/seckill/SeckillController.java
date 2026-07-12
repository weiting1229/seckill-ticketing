package com.seckill.seckill;

import com.seckill.auth.security.AuthUser;
import com.seckill.common.web.ApiResponse;
import com.seckill.seckill.dto.PurchaseResponse;
import com.seckill.seckill.dto.SeckillPurchaseRequest;
import com.seckill.seckill.dto.SeckillResultResponse;
import com.seckill.seckill.dto.SeckillTokenRequest;
import com.seckill.seckill.dto.SeckillTokenResponse;
import com.seckill.seckill.service.SeckillPurchaseService;
import com.seckill.seckill.service.SeckillTokenService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搶購 API(設計文件第 9 節)。/seckill/** 於 SecurityConfig 預設需認證(USER 即可)。
 * 例外一律拋出由全域處理器轉統一格式,controller 不做 try-catch。
 */
@RestController
@RequestMapping("/api/v1/seckill")
@PreAuthorize("hasRole('USER')")
public class SeckillController {

    private final SeckillTokenService tokenService;
    private final SeckillPurchaseService purchaseService;

    public SeckillController(SeckillTokenService tokenService, SeckillPurchaseService purchaseService) {
        this.tokenService = tokenService;
        this.purchaseService = purchaseService;
    }

    /**
     * 領取一次性搶購 token。校驗票種已上線且在開賣時間窗內,否則回 3xxx。
     */
    @PostMapping("/token")
    public ApiResponse<SeckillTokenResponse> token(
            @AuthenticationPrincipal AuthUser principal,
            @Valid @RequestBody SeckillTokenRequest request) {
        String token = tokenService.issue(principal.userId(), request.ticketTypeId());
        return ApiResponse.ok(new SeckillTokenResponse(
                token,
                String.valueOf(request.ticketTypeId()),
                SeckillTokenService.TOKEN_TTL.toSeconds()));
    }

    /**
     * 搶購下單(限流由攔截器前置)。全程 Redis + MQ,不觸 DB;成功回 requestId + orderId,
     * 售罄/重複/未就緒/無效 token 各回對應 3xxx。
     */
    @PostMapping("/purchase")
    public ApiResponse<PurchaseResponse> purchase(
            @AuthenticationPrincipal AuthUser principal,
            @Valid @RequestBody SeckillPurchaseRequest request) {
        return ApiResponse.ok(
                purchaseService.purchase(principal.userId(), request.ticketTypeId(), request.token()));
    }

    /** 輪詢排隊結果:QUEUING(無 key)/ SUCCESS(含 orderId)/ FAIL(含原因)。 */
    @GetMapping("/result/{requestId}")
    public ApiResponse<SeckillResultResponse> result(@PathVariable String requestId) {
        return ApiResponse.ok(purchaseService.getResult(requestId));
    }
}
