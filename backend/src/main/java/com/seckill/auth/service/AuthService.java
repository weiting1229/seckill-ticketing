package com.seckill.auth.service;

import com.seckill.auth.domain.Role;
import com.seckill.auth.domain.User;
import com.seckill.auth.domain.UserStatus;
import com.seckill.auth.dto.AccessTokenResponse;
import com.seckill.auth.dto.LoginResponse;
import com.seckill.auth.dto.RegisterResponse;
import com.seckill.auth.mapper.UserMapper;
import com.seckill.auth.security.JwtService;
import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.IdGenerator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 認證核心:註冊、登入、換發、登出。
 * 稽核日誌採結構化輸出;密碼與 token 一律不進日誌(設計文件第 10 節)。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final IdGenerator idGenerator;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, IdGenerator idGenerator,
                       JwtService jwtService, RefreshTokenService refreshTokenService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.idGenerator = idGenerator;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public RegisterResponse register(String username, String rawPassword) {
        if (userMapper.existsByUsername(username)) {
            throw new BusinessException(BizCode.USERNAME_ALREADY_EXISTS);
        }
        Instant now = Instant.now();
        User user = new User();
        user.setId(idGenerator.nextId());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(Role.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);

        log.info("使用者註冊成功 userId={} username={}", user.getId(), username);
        return new RegisterResponse(String.valueOf(user.getId()), username);
    }

    public LoginResponse login(String username, String rawPassword) {
        User user = userMapper.findByUsername(username);
        // 帳號不存在、密碼錯誤、帳號停用一律回同一錯誤,避免帳號枚舉
        if (user == null
                || user.getStatus() != UserStatus.ACTIVE
                || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            log.warn("登入失敗 username={}", username);
            throw new BusinessException(BizCode.INVALID_CREDENTIALS);
        }

        String accessToken = jwtService.generateAccessToken(user);
        JwtService.GeneratedRefreshToken refresh = jwtService.generateRefreshToken(user);
        refreshTokenService.store(user.getId(), refresh.jti(),
                Duration.ofSeconds(jwtService.refreshTtlSeconds()));

        log.info("登入成功 userId={} username={}", user.getId(), username);
        return LoginResponse.of(accessToken, refresh.token(), jwtService.accessTtlSeconds());
    }

    public AccessTokenResponse refresh(String refreshToken) {
        Claims claims;
        try {
            claims = jwtService.parse(refreshToken);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BusinessException(BizCode.REFRESH_TOKEN_INVALID);
        }
        if (!jwtService.isRefresh(claims)) {
            throw new BusinessException(BizCode.REFRESH_TOKEN_INVALID);
        }
        long userId = Long.parseLong(claims.getSubject());
        // 撤銷檢查:jti 必須與 Redis 中現存者相符
        if (!refreshTokenService.isValid(userId, claims.getId())) {
            throw new BusinessException(BizCode.REFRESH_TOKEN_INVALID);
        }
        User user = userMapper.findById(userId);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(BizCode.REFRESH_TOKEN_INVALID);
        }
        String accessToken = jwtService.generateAccessToken(user);
        return AccessTokenResponse.of(accessToken, jwtService.accessTtlSeconds());
    }

    public void logout(long userId) {
        refreshTokenService.revoke(userId);
        log.info("登出並撤銷 refresh token userId={}", userId);
    }
}
