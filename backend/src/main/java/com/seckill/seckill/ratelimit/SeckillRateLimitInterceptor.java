package com.seckill.seckill.ratelimit;

import com.seckill.auth.security.AuthUser;
import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.seckill.metrics.SeckillMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 搶購限流攔截器(掛在 /api/v1/seckill/purchase)。於 JWT 認證後、controller 前執行三層檢查:
 * 全域 QPS → 單 IP → 單用戶,任一超限即累加 {@code rate_limited} 指標並拋 {@link BizCode#RATE_LIMITED}
 * (由全域處理器回統一格式 HTTP 429)。以 {@code ||} 短路:前層已擋則不消耗後層 token。
 *
 * <p><b>取用戶端 IP(決策點,見 ADR 0004):</b>取 {@code X-Forwarded-For} 最左值為真實 client;
 * 缺失時退回 {@code getRemoteAddr()}。正式環境後端只經 Caddy 可達(設計文件第 10.4 節),建議 Caddy
 * 以 {@code header_up X-Forwarded-For {remote_host}} 覆寫,使該值不可由外部偽造;否則單 IP 限流對
 * 偽造 XFF 者為 best-effort(仍受全域與單用戶上限兜底)。
 */
@Component
public class SeckillRateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiterService rateLimiter;
    private final SeckillMetrics metrics;

    public SeckillRateLimitInterceptor(RateLimiterService rateLimiter, SeckillMetrics metrics) {
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long userId = currentUserId();
        boolean allowed = rateLimiter.tryGlobal()
                && rateLimiter.tryIp(clientIp(request))
                && (userId == null || rateLimiter.tryUser(userId));
        if (!allowed) {
            metrics.rateLimited();
            throw new BusinessException(BizCode.RATE_LIMITED);
        }
        return true;
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser user) {
            return user.userId();
        }
        return null;
    }

    /** 取真實 client IP:X-Forwarded-For 最左值,缺失則 getRemoteAddr()。 */
    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
