package com.seckill.seckill.service;

import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.event.domain.TicketType;
import com.seckill.event.domain.TicketTypeStatus;
import com.seckill.event.mapper.TicketTypeMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

/**
 * 一次性搶購 token(設計文件第 6、10 節,防刷第二層)。
 *
 * <p>下單前必須先領 token:{@link #issue} 校驗票種已上線且在開賣時間窗內,產生 256-bit
 * {@link SecureRandom} 亂數(base64url 編碼)作為 token。<b>明文只交給前端</b>,Redis
 * ({@code seckill:token:{userId}:{ticketTypeId}},60 秒 TTL)僅存其 SHA-256 雜湊——縱使
 * Redis 遭讀取也拿不到可用的 token(防禦縱深)。token 本身已高熵,雜湊用快速 SHA-256 即可,
 * 不需 bcrypt。
 *
 * <p>搶購時 {@link #consume} 先將前端提交的明文雜湊,再以 Lua 原子 GET 比對後 DEL,用後即焚,
 * 使腳本無法跳過頁面流程直刷下單接口;並避免兩個併發請求共用同一 token 都通過。
 */
@Service
public class SeckillTokenService {

    private static final Logger log = LoggerFactory.getLogger(SeckillTokenService.class);

    private static final String KEY_PREFIX = "seckill:token:";
    /** token 有效期(設計文件第 6 節:60 秒)。 */
    public static final Duration TOKEN_TTL = Duration.ofSeconds(60);
    /** token 亂數位元組數(256-bit)。 */
    private static final int TOKEN_BYTES = 32;
    /** 執行緒安全,靜態共用一個實例即可(避免每次領取都 new 一個,浪費效能)。 */
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate redisTemplate;
    private final TicketTypeMapper ticketTypeMapper;
    private final RedisScript<Long> checkScript;

    public SeckillTokenService(StringRedisTemplate redisTemplate, TicketTypeMapper ticketTypeMapper) {
        this.redisTemplate = redisTemplate;
        this.ticketTypeMapper = ticketTypeMapper;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seckill_token_check.lua")));
        script.setResultType(Long.class);
        this.checkScript = script;
    }

    /**
     * 領取一次性搶購 token。校驗票種存在、已上線(ONLINE)、且當下在 [seckillStart, seckillEnd] 時間窗內。
     *
     * @return 新產生的 token(UUID),已寫入 Redis 並設 60 秒 TTL
     * @throws BusinessException 票種不存在(2004)、未上線(3003)、未開賣(3001)、已結束(3002)
     */
    public String issue(long userId, long ticketTypeId) {
        TicketType t = ticketTypeMapper.findById(ticketTypeId);
        if (t == null) {
            throw new BusinessException(BizCode.TICKET_TYPE_NOT_FOUND);
        }
        if (t.getStatus() != TicketTypeStatus.ONLINE) {
            throw new BusinessException(BizCode.SECKILL_TICKET_NOT_ONLINE);
        }
        Instant now = Instant.now();
        if (now.isBefore(t.getSeckillStart())) {
            throw new BusinessException(BizCode.SECKILL_NOT_STARTED);
        }
        if (now.isAfter(t.getSeckillEnd())) {
            throw new BusinessException(BizCode.SECKILL_ENDED);
        }

        String token = generateToken();
        // Redis 僅存雜湊;明文只回給前端
        redisTemplate.opsForValue().set(key(userId, ticketTypeId), sha256(token), TOKEN_TTL);
        log.info("發放搶購 token userId={} ticketTypeId={}", userId, ticketTypeId);
        return token;
    }

    /**
     * 原子校驗並消耗 token(seckill_token_check.lua:GET 比對後 DEL)。
     * 先將前端提交的明文雜湊,再與 Redis 內的雜湊比對。
     *
     * @return true 校驗通過且已消耗;false 不符 / 過期 / 已用 / 偽造
     */
    public boolean consume(long userId, long ticketTypeId, String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        Long result = redisTemplate.execute(checkScript, List.of(key(userId, ticketTypeId)), sha256(token));
        return result != null && result == 1L;
    }

    private static String generateToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buf);
        return B64URL.encodeToString(buf);
    }

    /** SHA-256 → base64url。token 已高熵,快速雜湊即足以抵禦(不需 bcrypt)。 */
    private static String sha256(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return B64URL.encodeToString(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用(標準 JDK 必備)", e);
        }
    }

    private String key(long userId, long ticketTypeId) {
        return KEY_PREFIX + userId + ":" + ticketTypeId;
    }
}
