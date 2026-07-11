package com.seckill.event.domain;

/**
 * 活動狀態(設計文件第 5 節 events.status)。MyBatis 以 name 存為 VARCHAR。
 *
 * <p>狀態機:DRAFT → PUBLISHED → CLOSED(單向);同狀態視為冪等 no-op。
 * 一旦 CLOSED 即為終態,不可再轉。狀態轉移於 service 層以 {@link #canTransitionTo} 校驗。
 */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    CLOSED;

    /** 是否允許由目前狀態轉移到 {@code target}(相同狀態允許,視為 no-op)。 */
    public boolean canTransitionTo(EventStatus target) {
        if (this == target) {
            return true;
        }
        return switch (this) {
            case DRAFT -> target == PUBLISHED || target == CLOSED;
            case PUBLISHED -> target == CLOSED;
            case CLOSED -> false;
        };
    }
}
