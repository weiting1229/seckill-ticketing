package com.seckill.seckill.ratelimit;

import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.seckill.metrics.SeckillMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 領取 token 限流攔截器(掛在 /api/v1/seckill/token)。較寬鬆、<b>以 userId 為 key</b>:
 * 保護 token 端點的 DB 查詢不被單一帳號狂刷。與 purchase 的三層限流分離——單用戶 2/s 專屬 purchase,
 * 此處僅套用 {@code token-user-capacity}(預設 5/s)。以 userId 為 key 亦避免測試共用 localhost IP 互擾。
 */
@Component
public class SeckillTokenRateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiterService rateLimiter;
    private final SeckillMetrics metrics;

    public SeckillTokenRateLimitInterceptor(RateLimiterService rateLimiter, SeckillMetrics metrics) {
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long userId = ClientRequestInfo.currentUserId();
        if (userId != null && !rateLimiter.tryTokenUser(userId)) {
            metrics.rateLimited();
            throw new BusinessException(BizCode.RATE_LIMITED);
        }
        return true;
    }
}
