package com.seckill.admin;

import com.seckill.auth.domain.Role;
import com.seckill.auth.domain.User;
import com.seckill.auth.domain.UserStatus;
import com.seckill.auth.mapper.UserMapper;
import com.seckill.common.id.IdGenerator;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * ADMIN 帳號啟動種子。註冊 API 只建立 USER,而 admin 端點需要 ADMIN 才能操作與驗收。
 *
 * <p>決策(見 ADR 0003):採<b>環境變數驅動</b>而非提交式 Flyway seed——避免把可登入的
 * 管理員憑證寫進 repo。僅當 {@code SECKILL_ADMIN_USERNAME} 與 {@code SECKILL_ADMIN_PASSWORD}
 * 皆提供時才建立;帳號已存在則跳過(冪等)。密碼經 BCrypt 雜湊後儲存,絕不進日誌。
 */
@Component
public class AdminBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final IdGenerator idGenerator;
    private final String adminUsername;
    private final String adminPassword;

    public AdminBootstrap(UserMapper userMapper, PasswordEncoder passwordEncoder, IdGenerator idGenerator,
                          @Value("${seckill.admin.username:}") String adminUsername,
                          @Value("${seckill.admin.password:}") String adminPassword) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.idGenerator = idGenerator;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (adminUsername.isBlank() || adminPassword.isBlank()) {
            log.info("未設定 seckill.admin.username/password,略過 ADMIN 種子");
            return;
        }
        if (userMapper.existsByUsername(adminUsername)) {
            log.info("ADMIN 帳號已存在,略過建立 username={}", adminUsername);
            return;
        }
        Instant now = Instant.now();
        User admin = new User();
        admin.setId(idGenerator.nextId());
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setRole(Role.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);
        userMapper.insert(admin);
        log.info("已建立 ADMIN 種子帳號 userId={} username={}", admin.getId(), adminUsername);
    }
}
