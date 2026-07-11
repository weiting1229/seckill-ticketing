package com.seckill.auth.security;

/**
 * 認證後放入 SecurityContext 的 principal。
 * controller 以 {@code @AuthenticationPrincipal AuthUser} 取得目前使用者。
 */
public record AuthUser(Long userId, String username, String role) {
}
