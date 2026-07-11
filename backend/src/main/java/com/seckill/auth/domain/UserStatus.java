package com.seckill.auth.domain;

/** 使用者狀態。非 ACTIVE 者不得登入。 */
public enum UserStatus {
    ACTIVE,
    DISABLED
}
