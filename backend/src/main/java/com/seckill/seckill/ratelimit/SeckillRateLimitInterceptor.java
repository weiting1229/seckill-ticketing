package com.seckill.seckill.ratelimit;

import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.metrics.SeckillMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 搶購下單限流攔截器(掛在 /api/v1/seckill/purchase)。於 JWT 認證後、controller 前以一次腳本呼叫檢查三層:
 * 全域 QPS、單 IP、單用戶,任一超限即累加 {@code rate_limited} 指標並拋 {@link BizCode#RATE_LIMITED}
 * (由全域處理器回統一格式 HTTP 429)。三層全有或全無:被擋下的請求不消耗任何一層的 token(ADR 0010 §3)。
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
        Optional<RateLimitLayer> rejectedBy =
                rateLimiter.checkPurchase(ClientRequestInfo.clientIp(request), userId);
        if (rejectedBy.isPresent()) {
            metrics.rateLimited(rejectedBy.get().tag());
            throw new BusinessException(BizCode.RATE_LIMITED);
        }
        return true;
    }
}
