package com.seckill.seckill.ratelimit;

/** 限流層;{@link #tag()} 為指標 {@code seckill.ratelimit.rejected} 的 layer 標籤值。 */
public enum RateLimitLayer {
    GLOBAL("global"),
    IP("ip"),
    USER("user"),
    TOKEN_USER("token_user");

    private final String tag;

    RateLimitLayer(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
