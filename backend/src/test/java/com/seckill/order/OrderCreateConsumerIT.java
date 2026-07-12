package com.seckill.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.seckill.auth.domain.Role;
import com.seckill.common.id.IdGenerator;
import com.seckill.config.RabbitConfig;
import com.seckill.order.domain.Order;
import com.seckill.order.domain.OrderStatus;
import com.seckill.order.mapper.OrderMapper;
import com.seckill.order.mq.OrderMessage;
import com.seckill.order.mq.OrderMessagePublisher;
import com.seckill.order.service.OrderResultCache;
import com.seckill.support.AbstractAdminIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 建單消費者(子項 E)整合測試:正常落庫、冪等重投、DB 售罄回補 + FAIL、未預期例外重試進 DLQ。
 * 非同步流程以 Awaitility 等 drain 完再斷言。
 */
class OrderCreateConsumerIT extends AbstractAdminIntegrationTest {

    private static final String STOCK_KEY = "seckill:stock:";
    private static final String BOUGHT_KEY = "seckill:bought:";

    @Autowired
    OrderMessagePublisher publisher;

    @Autowired
    OrderResultCache resultCache;

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    IdGenerator idGenerator;

    private String createEvent(String admin) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", "消費者測試");
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

    private OrderMessage message(String requestId, long userId, long ticketTypeId, long orderId) {
        return new OrderMessage(requestId, userId, ticketTypeId, orderId, System.currentTimeMillis());
    }

    private Integer dbStock(long ticketTypeId) {
        return jdbcTemplate.queryForObject(
                "SELECT stock_remaining FROM ticket_types WHERE id = ?", Integer.class, ticketTypeId);
    }

    // ---- (a) 正常搶購 end-to-end 落庫 ----

    @Test
    void normalPurchasePersistsOrderStockLogAndResult() {
        String admin = createAdminToken();
        long ttId = createTicketType(admin, createEvent(admin), 100);
        long userId = insertUser(Role.USER).getId();
        long orderId = idGenerator.nextId();
        String requestId = UUID.randomUUID().toString();

        publisher.publish(message(requestId, userId, ttId, orderId));

        await().atMost(Duration.ofSeconds(15))
                .until(() -> ("SUCCESS:" + orderId).equals(resultCache.get(requestId)));

        Order order = orderMapper.findByRequestId(requestId);
        assertThat(order).isNotNull();
        assertThat(order.getId()).isEqualTo(orderId);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getExpireAt()).isNotNull();
        assertThat(dbStock(ttId)).isEqualTo(99);

        Integer logCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_logs WHERE order_id = ? AND type = 'DEDUCT' AND delta = -1",
                Integer.class, orderId);
        assertThat(logCount).isEqualTo(1);
    }

    // ---- (d) 重複投遞同一訊息 → 只產生一筆訂單(冪等)----

    @Test
    void duplicateDeliveryYieldsSingleOrder() {
        String admin = createAdminToken();
        long ttId = createTicketType(admin, createEvent(admin), 100);
        long userId = insertUser(Role.USER).getId();
        long orderId = idGenerator.nextId();
        String requestId = UUID.randomUUID().toString();
        OrderMessage msg = message(requestId, userId, ttId, orderId);

        publisher.publish(msg);
        await().atMost(Duration.ofSeconds(15))
                .until(() -> orderMapper.findByRequestId(requestId) != null);
        assertThat(dbStock(ttId)).isEqualTo(99);

        // 重複投遞:唯一鍵衝突 → 冪等,不應再建單、不應再扣庫存
        publisher.publish(msg);
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6)).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE request_id = ?", Integer.class, requestId);
            assertThat(count).isEqualTo(1);
            assertThat(dbStock(ttId)).isEqualTo(99);
        });
    }

    // ---- (e) DB 扣減失敗 → 回補 Redis 庫存、result 為 FAIL、訂單回滾 ----

    @Test
    void dbDeductFailureRevertsRedisAndMarksFail() {
        String admin = createAdminToken();
        long ttId = createTicketType(admin, createEvent(admin), 5);
        long userId = insertUser(Role.USER).getId();

        // 讓 DB 售罄;並模擬熱路徑已扣 Redis(stock=3、bought 含 user)
        jdbcTemplate.update("UPDATE ticket_types SET stock_remaining = 0 WHERE id = ?", ttId);
        redisTemplate.opsForValue().set(STOCK_KEY + ttId, "3");
        redisTemplate.opsForSet().add(BOUGHT_KEY + ttId, String.valueOf(userId));

        long orderId = idGenerator.nextId();
        String requestId = UUID.randomUUID().toString();
        publisher.publish(message(requestId, userId, ttId, orderId));

        await().atMost(Duration.ofSeconds(15))
                .until(() -> ("FAIL:STOCK_DEPLETED").equals(resultCache.get(requestId)));

        // 訂單回滾(不存在)、Redis 已回補、已購移除
        assertThat(orderMapper.findByRequestId(requestId)).isNull();
        assertThat(redisTemplate.opsForValue().get(STOCK_KEY + ttId)).isEqualTo("4");
        assertThat(redisTemplate.opsForSet().isMember(BOUGHT_KEY + ttId, String.valueOf(userId))).isFalse();
    }

    // ---- 未預期例外(票種不存在)→ 重試 3 次後死信至 DLQ ----

    @Test
    void unexpectedFailureRetriesThenDeadLetters() {
        long userId = insertUser(Role.USER).getId();
        long bogusTicketId = 999_999_999L; // 不存在 → findById null → IllegalStateException
        String requestId = UUID.randomUUID().toString();

        publisher.publish(message(requestId, userId, bogusTicketId, idGenerator.nextId()));

        await().atMost(Duration.ofSeconds(20)).until(() -> dlqContains(requestId));
        // DLQ 路徑不寫結果:仍為 QUEUING(無 key)
        assertThat(resultCache.get(requestId)).isNull();
    }

    /** 從 DLQ 逐則取出,找到目標 requestId 即為命中(順帶排掉其他測試殘留訊息)。 */
    private boolean dlqContains(String requestId) {
        for (int i = 0; i < 100; i++) {
            Object m = rabbitTemplate.receiveAndConvert(RabbitConfig.ORDER_DLQ);
            if (m == null) {
                return false;
            }
            if (m instanceof OrderMessage om && requestId.equals(om.requestId())) {
                return true;
            }
        }
        return false;
    }
}
