package com.seckill.config;

import com.seckill.seckill.ratelimit.SeckillRateLimitInterceptor;
import com.seckill.seckill.ratelimit.SeckillTokenRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 註冊搶購限流攔截器(設計文件第 10 節):
 * <ul>
 *   <li>下單 /api/v1/seckill/purchase:全域 + 單 IP + 單用戶 2/s(三層)</li>
 *   <li>領 token /api/v1/seckill/token:較寬鬆、以 userId 為 key 的單用戶 5/s(保護 DB 查詢)</li>
 * </ul>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final SeckillRateLimitInterceptor purchaseRateLimitInterceptor;
    private final SeckillTokenRateLimitInterceptor tokenRateLimitInterceptor;

    public WebMvcConfig(SeckillRateLimitInterceptor purchaseRateLimitInterceptor,
                        SeckillTokenRateLimitInterceptor tokenRateLimitInterceptor) {
        this.purchaseRateLimitInterceptor = purchaseRateLimitInterceptor;
        this.tokenRateLimitInterceptor = tokenRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(purchaseRateLimitInterceptor)
                .addPathPatterns("/api/v1/seckill/purchase");
        registry.addInterceptor(tokenRateLimitInterceptor)
                .addPathPatterns("/api/v1/seckill/token");
    }
}
