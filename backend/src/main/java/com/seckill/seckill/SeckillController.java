package com.seckill.seckill;

import com.seckill.auth.security.AuthUser;
import com.seckill.common.web.ApiResponse;
import com.seckill.seckill.dto.SeckillTokenRequest;
import com.seckill.seckill.dto.SeckillTokenResponse;
import com.seckill.seckill.service.SeckillTokenService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    public SeckillController(SeckillTokenService tokenService) {
        this.tokenService = tokenService;
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
}
