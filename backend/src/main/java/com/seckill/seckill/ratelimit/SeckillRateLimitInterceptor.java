package com.seckill.seckill.ratelimit;

import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.metrics.SeckillMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 搶購下單限流攔截器(掛在 /api/v1/seckill/purchase)。於 JWT 認證後、controller 前執行三層檢查:
 * 全域 QPS → 單 IP → 單用戶,任一超限即累加 {@code rate_limited} 指標並拋 {@link BizCode#RATE_LIMITED}
 * (由全域處理器回統一格式 HTTP 429)。以 {@code &&} 短路:前層已擋則不消耗後層 token。
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
        Long userId = ClientRequestInfo.currentUserId();
        boolean allowed = rateLimiter.tryGlobal()
                && rateLimiter.tryIp(ClientRequestInfo.clientIp(request))
                && (userId == null || rateLimiter.tryUser(userId));
        if (!allowed) {
            metrics.rateLimited();
            throw new BusinessException(BizCode.RATE_LIMITED);
        }
        return true;
    }
}
