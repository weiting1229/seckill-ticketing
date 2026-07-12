package com.seckill.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.seckill.auth.domain.Role;
import com.seckill.common.id.IdGenerator;
import com.seckill.config.RabbitConfig;
import com.seckill.event.dto.ReconcileResponse;
import com.seckill.event.service.ReconcileService;
import com.seckill.order.domain.Order;
import com.seckill.order.domain.OrderStatus;
import com.seckill.order.mapper.OrderMapper;
import com.seckill.order.mq.OrderDelayMessage;
import com.seckill.order.mq.OrderMessage;
import com.seckill.order.mq.OrderMessagePublisher;
import com.seckill.support.AbstractAdminIntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 延遲/超時取消(子項 2)整合測試:延遲佇列 TTL → DLX → 超時消費者 → 取消 + 三方回補,
 * 以及「建單成功發延遲訊息、冪等路徑不發」的串接。
 *
 * <p>提速不覆寫佇列 {@code x-message-ttl}(避免同名 durable 佇列跨 cached context 以不同參數重複宣告),
 * 改以逐訊息 {@code expiration} 直接發短延遲訊息(RabbitMQ 取 queue 與 per-message TTL 的最小值)。
 * 斷言最終 DB / Redis 狀態(而非特定 context 消費),避開多 context 競爭消費的 flakiness(已知坑)。
 */
class OrderTimeoutCancelIT extends AbstractAdminIntegrationTest {

    private static final String STOCK_KEY = "seckill:stock:";
    private static final String BOUGHT_KEY = "seckill:bought:";
    /** 逐訊息短延遲;>queue 消費 latency 以免 dead-letter 後在超時佇列又過期,<測試等待上限。 */
    private static final String SHORT_EXPIRATION_MS = "800";

    @Autowired
    OrderMessagePublisher orderMessagePublisher;

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    ReconcileService reconcileService;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    IdGenerator idGenerator;

    @BeforeEach
    void purgeDelayTopology() {
        // 清掉其他 cached context 殘留的延遲/超時訊息,避免延遲佇列 head-of-line 阻塞本測試的短延遲訊息
        amqpAdmin.purgeQueue(RabbitConfig.ORDER_DELAY_QUEUE, false);
        amqpAdmin.purgeQueue(RabbitConfig.ORDER_TIMEOUT_QUEUE, false);
    }

    // ---- 測試夾具 ----

    private String createEvent(String admin) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", "超時取消測試");
        m.put("venue", "Taipei");
        m.put("eventTime", Instant.now().plus(10, ChronoUnit.DAYS).toString());
        return json(post("/api/v1/admin/events", admin, m)).path("data").path("id").asText();
    }

    private long createTicketType(String admin, String eventId, int totalStock) {
        Map<String, Object> m = new HashMap<>();
        m.put("eventId", eventId);
        m.put("name", "看台");
        m.put("price", "1200.00");
        m.put("totalStock", totalStock);
        m.put("seckillStart", Instant.now().minus(1, ChronoUnit.HOURS).toString());
        m.put("seckillEnd", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        return Long.parseLong(json(post("/api/v1/admin/ticket-types", admin, m)).path("data").path("id").asText());
    }

    /** 模擬「一次已成交、待支付」的一致狀態(DB 扣 1、Redis 扣 1、已購含 user、PENDING、DEDUCT 流水)。 */
    private long seedPendingSoldOrder(long eventId, long ticketTypeId, long userId, int totalStock) {
        int remaining = totalStock - 1;
        jdbcTemplate.update("UPDATE ticket_types SET stock_remaining = ? WHERE id = ?", remaining, ticketTypeId);
        redisTemplate.opsForValue().set(STOCK_KEY + ticketTypeId, String.valueOf(remaining));
        redisTemplate.opsForSet().add(BOUGHT_KEY + ticketTypeId, String.valueOf(userId));

        long orderId = idGenerator.nextId();
        Instant now = Instant.now();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setEventId(eventId);
        order.setTicketTypeId(ticketTypeId);
        order.setPrice(new BigDecimal("1200.00"));
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setRequestId(UUID.randomUUID().toString());
        order.setCreatedAt(now.minus(20, ChronoUnit.MINUTES));
        order.setUpdatedAt(now.minus(20, ChronoUnit.MINUTES));
        order.setExpireAt(now.minus(5, ChronoUnit.MINUTES));
        orderMapper.insert(order);

        jdbcTemplate.update(
                "INSERT INTO stock_logs (id, ticket_type_id, order_id, delta, type, created_at) "
                        + "VALUES (?, ?, ?, -1, 'DEDUCT', ?)",
                idGenerator.nextId(), ticketTypeId, orderId, java.sql.Timestamp.from(now));
        return orderId;
    }

    private Integer dbStock(long ticketTypeId) {
        return jdbcTemplate.queryForObject(
                "SELECT stock_remaining FROM ticket_types WHERE id = ?", Integer.class, ticketTypeId);
    }

    // ---- (b) 超時取消 end-to-end:短延遲 → DLX → 消費者取消 → 三方對帳一致 ----

    @Test
    void delayedMessageExpiresAndCancelsOrderWithConsistentReconcile() {
        String admin = createAdminToken();
        long eventId = Long.parseLong(createEvent(admin));
        long ttId = createTicketType(admin, String.valueOf(eventId), 10);
        long userId = insertUser(Role.USER).getId();
        long orderId = seedPendingSoldOrder(eventId, ttId, userId, 10);

        // 直接發一則短延遲(800ms)訊息到延遲交換機;到期後 dead-letter 至 order.timeout.queue 觸發取消
        rabbitTemplate.convertAndSend(
                RabbitConfig.ORDER_DELAY_EXCHANGE, RabbitConfig.ORDER_DELAY_ROUTING_KEY,
                new OrderDelayMessage(orderId, ttId, userId),
                m -> {
                    m.getMessageProperties().setExpiration(SHORT_EXPIRATION_MS);
                    return m;
                });

        // 斷言最終狀態:訂單轉 EXPIRED
        await().atMost(Duration.ofSeconds(20))
                .until(() -> orderMapper.findById(orderId).getStatus() == OrderStatus.EXPIRED);

        // DB commit 後才回補 Redis,故對帳一致以 await 涵蓋該微小時窗
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ReconcileResponse rec = reconcileService.reconcile(ttId);
            assertThat(rec.consistent()).isTrue();
            assertThat(rec.soldByDb()).isZero();
            assertThat(rec.validOrderCount()).isZero();
        });

        assertThat(orderMapper.findById(orderId).getPaidAt()).isNull(); // paid_at 不動
        assertThat(dbStock(ttId)).isEqualTo(10);
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo("10");
        assertThat(redisTemplate.opsForSet().isMember(BOUGHT_KEY + ttId, String.valueOf(userId))).isFalse();
        Integer revertLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_logs WHERE order_id = ? AND type = 'REVERT' AND delta = 1",
                Integer.class, orderId);
        assertThat(revertLogs).isEqualTo(1);
    }

    // ---- 建單成功發一則延遲訊息;冪等(重複投遞)路徑不再發 ----

    @Test
    void createSuccessPublishesExactlyOneDelayMessageAndDuplicatePublishesNone() {
        String admin = createAdminToken();
        long ttId = createTicketType(admin, createEvent(admin), 100);
        long userId = insertUser(Role.USER).getId();
        long orderId = idGenerator.nextId();
        String requestId = UUID.randomUUID().toString();
        OrderMessage msg = new OrderMessage(requestId, userId, ttId, orderId, System.currentTimeMillis());

        // 成功建單一次
        orderMessagePublisher.publish(msg);
        await().atMost(Duration.ofSeconds(15)).until(() -> orderMapper.findByRequestId(requestId) != null);

        // 重複投遞同一訊息:唯一鍵衝突走冪等路徑,不應再發延遲訊息
        orderMessagePublisher.publish(msg);
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE request_id = ?", Integer.class, requestId);
            assertThat(count).isEqualTo(1);
        });

        // 延遲佇列(15 分鐘 TTL,無消費者,訊息滯留)中,屬本訂單的延遲訊息恰一則
        assertThat(drainDelayQueueCountFor(orderId)).isEqualTo(1);
    }

    /** 逐則抽乾延遲佇列,計數屬指定訂單的延遲訊息(順帶排掉其他測試殘留)。 */
    private int drainDelayQueueCountFor(long orderId) {
        int matched = 0;
        for (int i = 0; i < 500; i++) {
            Object m = rabbitTemplate.receiveAndConvert(RabbitConfig.ORDER_DELAY_QUEUE);
            if (m == null) {
                break;
            }
            if (m instanceof OrderDelayMessage dm && dm.orderId() == orderId) {
                matched++;
            }
        }
        return matched;
    }
}
