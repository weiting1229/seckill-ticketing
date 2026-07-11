package com.seckill.config;

import com.seckill.seckill.ratelimit.SeckillRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 註冊搶購限流攔截器,僅作用於搶購下單熱路徑 /api/v1/seckill/purchase(設計文件第 10 節)。
 * token 領取等其餘 seckill 端點不套用單用戶 2/s 限制(該限制專屬 purchase)。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final SeckillRateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfig(SeckillRateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/seckill/purchase");
    }
}
