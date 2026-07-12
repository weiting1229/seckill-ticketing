package com.seckill.seckill.ratelimit;

import com.seckill.auth.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 限流攔截器共用:自 SecurityContext 取 userId、自請求取真實 client IP。 */
final class ClientRequestInfo {

    private ClientRequestInfo() {
    }

    /** 目前已認證使用者的 userId;未認證或非 AuthUser 時回 null。 */
    static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser user) {
            return user.userId();
        }
        return null;
    }

    /**
     * 取真實 client IP(決策點,見 ADR 0004):取 {@code X-Forwarded-For} 最左值,缺失時退回
     * {@code getRemoteAddr()}。正式環境後端只經 Caddy 可達(設計文件第 10.4 節),建議 Caddy 以
     * {@code header_up X-Forwarded-For {remote_host}} 覆寫使該值不可偽造;否則單 IP 限流對偽造
     * XFF 者為 best-effort(仍受全域與單用戶上限兜底)。
     */
    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
