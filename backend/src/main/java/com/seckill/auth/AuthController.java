package com.seckill.auth;

import com.seckill.auth.dto.AccessTokenResponse;
import com.seckill.auth.dto.LoginRequest;
import com.seckill.auth.dto.LoginResponse;
import com.seckill.auth.dto.MeResponse;
import com.seckill.auth.dto.RefreshRequest;
import com.seckill.auth.dto.RegisterRequest;
import com.seckill.auth.dto.RegisterResponse;
import com.seckill.auth.security.AuthUser;
import com.seckill.auth.service.AuthService;
import com.seckill.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 認證 API(設計文件第 9 節)。register / login / refresh 匿名可存取(於 SecurityConfig 明列);
 * me / logout 需要認證。例外一律拋出,由全域處理器轉統一格式,controller 內不做 try-catch。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request.username(), request.password()));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.username(), request.password()));
    }

    @PostMapping("/refresh")
    public ApiResponse<AccessTokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AuthUser principal) {
        authService.logout(principal.userId());
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal AuthUser principal) {
        return ApiResponse.ok(new MeResponse(
                String.valueOf(principal.userId()), principal.username(), principal.role()));
    }
}
