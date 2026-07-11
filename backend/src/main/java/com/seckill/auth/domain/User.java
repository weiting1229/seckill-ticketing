package com.seckill.auth.domain;

import java.time.Instant;
import lombok.Data;

/**
 * 使用者(對應 users 表)。主鍵為 Snowflake ID。
 * 密碼以 BCrypt 雜湊後存於 passwordHash,原始密碼絕不留存、不進日誌。
 */
@Data
public class User {
    private Long id;
    private String username;
    private String passwordHash;
    private Role role;
    private UserStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
