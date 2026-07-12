package com.seckill.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.seckill.auth.domain.Role;
import com.seckill.auth.domain.User;
import com.seckill.common.id.IdGenerator;
import com.seckill.order.domain.Order;
import com.seckill.order.domain.OrderStatus;
import com.seckill.order.mapper.OrderMapper;
import com.seckill.support.AbstractAdminIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 我的訂單查詢(子項 4)整合測試:分頁(created_at DESC)、歸屬隔離、詳情歸屬校驗(他人 404)、空清單。
 */
class OrderQueryIT extends AbstractAdminIntegrationTest {

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    IdGenerator idGenerator;

    /** 為指定 user 插入一筆 PENDING 訂單(event/ticketType 無 FK,用不重複的 Snowflake 值即可)。 */
    private long insertOrderAt(long userId, Instant createdAt) {
        long orderId = idGenerator.nextId();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setEventId(idGenerator.nextId());
        order.setTicketTypeId(idGenerator.nextId()); // 每筆不同,避開 uq_orders_user_ticket
        order.setPrice(new BigDecimal("1200.00"));
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setRequestId(UUID.randomUUID().toString());
        order.setCreatedAt(createdAt);
        order.setUpdatedAt(createdAt);
        order.setExpireAt(createdAt.plus(15, ChronoUnit.MINUTES));
        orderMapper.insert(order);
        return orderId;
    }

    // ---- 分頁 + created_at DESC + 歸屬隔離 ----

    @Test
    void listMyOrdersPaginatedNewestFirstAndIsolatedPerUser() {
        User user = insertUser(Role.USER);
        String token = tokenFor(user);
        Instant base = Instant.now();
        long oldest = insertOrderAt(user.getId(), base.minus(30, ChronoUnit.SECONDS));
        long middle = insertOrderAt(user.getId(), base.minus(20, ChronoUnit.SECONDS));
        long newest = insertOrderAt(user.getId(), base.minus(10, ChronoUnit.SECONDS));
        // 另一使用者的訂單不得出現在我的清單
        insertOrderAt(insertUser(Role.USER).getId(), base);

        // 第 1 頁(size=2):total=3,依 created_at DESC → [newest, middle]
        ResponseEntity<String> p1 = get("/api/v1/orders?page=1&size=2", token);
        assertThat(p1.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode d1 = json(p1).path("data");
        assertThat(d1.path("total").asLong()).isEqualTo(3);
        assertThat(d1.path("page").asInt()).isEqualTo(1);
        assertThat(d1.path("size").asInt()).isEqualTo(2);
        JsonNode items1 = d1.path("items");
        assertThat(items1).hasSize(2);
        assertThat(items1.get(0).path("id").asText()).isEqualTo(String.valueOf(newest));
        assertThat(items1.get(1).path("id").asText()).isEqualTo(String.valueOf(middle));
        // ID 為 String
        assertThat(items1.get(0).path("id").isTextual()).isTrue();

        // 第 2 頁:剩 [oldest]
        ResponseEntity<String> p2 = get("/api/v1/orders?page=2&size=2", token);
        JsonNode items2 = json(p2).path("data").path("items");
        assertThat(items2).hasSize(1);
        assertThat(items2.get(0).path("id").asText()).isEqualTo(String.valueOf(oldest));
    }

    // ---- 我的訂單詳情:本人可讀 ----

    @Test
    void getMyOrderReturnsOwnOrder() {
        User user = insertUser(Role.USER);
        String token = tokenFor(user);
        long orderId = insertOrderAt(user.getId(), Instant.now());

        ResponseEntity<String> resp = get("/api/v1/orders/" + orderId, token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = json(resp).path("data");
        assertThat(data.path("id").asText()).isEqualTo(String.valueOf(orderId));
        assertThat(data.path("status").asText()).isEqualTo("PENDING_PAYMENT");
    }

    // ---- 他人訂單詳情 → 404 / 4001(不洩漏存在性)----

    @Test
    void getOthersOrderReturns404() {
        User owner = insertUser(Role.USER);
        long orderId = insertOrderAt(owner.getId(), Instant.now());
        String attackerToken = tokenFor(insertUser(Role.USER));

        ResponseEntity<String> resp = get("/api/v1/orders/" + orderId, attackerToken);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(resp).path("code").asInt()).isEqualTo(4001);
    }

    // ---- 不存在的訂單 → 404 / 4001 ----

    @Test
    void getNonexistentOrderReturns404() {
        String token = tokenFor(insertUser(Role.USER));
        ResponseEntity<String> resp = get("/api/v1/orders/" + idGenerator.nextId(), token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(resp).path("code").asInt()).isEqualTo(4001);
    }

    // ---- 無訂單 → 空清單、total=0 ----

    @Test
    void listReturnsEmptyForUserWithoutOrders() {
        String token = tokenFor(insertUser(Role.USER));
        ResponseEntity<String> resp = get("/api/v1/orders", token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = json(resp).path("data");
        assertThat(data.path("total").asLong()).isZero();
        assertThat(data.path("items")).isEmpty();
    }
}
