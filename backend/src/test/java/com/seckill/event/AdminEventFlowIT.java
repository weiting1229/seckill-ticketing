package com.seckill.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.seckill.support.AbstractAdminIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 活動 / 票種 admin CRUD 整合測試:
 * 授權(USER 403 / 匿名 401 / ADMIN 正常)、狀態機、刪除守衛、時間區間校驗。
 */
class AdminEventFlowIT extends AbstractAdminIntegrationTest {

    private Map<String, Object> eventBody(String title) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        m.put("description", "desc");
        m.put("venue", "Taipei Arena");
        m.put("eventTime", Instant.now().plus(30, ChronoUnit.DAYS).toString());
        return m;
    }

    private Map<String, Object> ticketBody(String eventId, int totalStock) {
        Map<String, Object> m = new HashMap<>();
        m.put("eventId", eventId);
        m.put("name", "搖滾區");
        m.put("price", "2800.00");
        m.put("totalStock", totalStock);
        m.put("seckillStart", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        m.put("seckillEnd", Instant.now().plus(2, ChronoUnit.DAYS).toString());
        return m;
    }

    // ---- 授權 ----

    @Test
    void userTokenShouldGet403OnAdminEndpoint() {
        String userToken = createUserToken();
        ResponseEntity<String> resp = post("/api/v1/admin/events", userToken, eventBody("t"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(resp).path("code").asInt()).isEqualTo(1403);
    }

    @Test
    void anonymousShouldGet401OnAdminEndpoint() {
        ResponseEntity<String> resp = post("/api/v1/admin/events", null, eventBody("t"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(resp).path("code").asInt()).isEqualTo(1401);
    }

    // ---- ADMIN CRUD 快樂路徑 ----

    @Test
    void adminCanCrudEventAndTicketType() {
        String admin = createAdminToken();

        // create event(預設 DRAFT)
        ResponseEntity<String> created = post("/api/v1/admin/events", admin, eventBody("演唱會 A"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode ev = json(created).path("data");
        String eventId = ev.path("id").asText();
        assertThat(ev.path("status").asText()).isEqualTo("DRAFT");
        assertThat(eventId).isNotBlank();

        // get by id
        ResponseEntity<String> got = get("/api/v1/admin/events/" + eventId, admin);
        assertThat(json(got).path("data").path("title").asText()).isEqualTo("演唱會 A");

        // list(含各狀態)
        ResponseEntity<String> list = get("/api/v1/admin/events?page=1&size=50", admin);
        assertThat(json(list).path("data").path("items").isArray()).isTrue();

        // create ticket type under event
        ResponseEntity<String> tt = post("/api/v1/admin/ticket-types", admin, ticketBody(eventId, 100));
        assertThat(tt.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode ttData = json(tt).path("data");
        String ticketTypeId = ttData.path("id").asText();
        assertThat(ttData.path("status").asText()).isEqualTo("OFFLINE");
        assertThat(ttData.path("stockRemaining").asInt()).isEqualTo(100);

        // update event → PUBLISHED
        Map<String, Object> upd = eventBody("演唱會 A(改)");
        upd.put("status", "PUBLISHED");
        ResponseEntity<String> published = put("/api/v1/admin/events/" + eventId, admin, upd);
        assertThat(json(published).path("data").path("status").asText()).isEqualTo("PUBLISHED");

        // 刪除守衛:含票種不可刪
        ResponseEntity<String> delGuarded = delete("/api/v1/admin/events/" + eventId, admin);
        assertThat(delGuarded.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(delGuarded).path("code").asInt()).isEqualTo(2003);

        // 刪票種(OFFLINE 可刪)
        ResponseEntity<String> delTt = delete("/api/v1/admin/ticket-types/" + ticketTypeId, admin);
        assertThat(delTt.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 票種刪除後,活動可刪
        ResponseEntity<String> delEv = delete("/api/v1/admin/events/" + eventId, admin);
        assertThat(delEv.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 刪除後查不到
        assertThat(get("/api/v1/admin/events/" + eventId, admin).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- 狀態機 ----

    @Test
    void illegalStatusTransitionShouldReturn2002() {
        String admin = createAdminToken();
        String eventId = json(post("/api/v1/admin/events", admin, eventBody("狀態機")))
                .path("data").path("id").asText();

        // DRAFT → CLOSED(合法)
        Map<String, Object> toClosed = eventBody("狀態機");
        toClosed.put("status", "CLOSED");
        assertThat(put("/api/v1/admin/events/" + eventId, admin, toClosed).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // CLOSED → PUBLISHED(非法)
        Map<String, Object> toPublished = eventBody("狀態機");
        toPublished.put("status", "PUBLISHED");
        ResponseEntity<String> resp = put("/api/v1/admin/events/" + eventId, admin, toPublished);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(resp).path("code").asInt()).isEqualTo(2002);
    }

    // ---- 錯誤路徑 ----

    @Test
    void ticketTypeWithInvalidTimeRangeShouldReturn2006() {
        String admin = createAdminToken();
        String eventId = json(post("/api/v1/admin/events", admin, eventBody("時間")))
                .path("data").path("id").asText();

        Map<String, Object> bad = ticketBody(eventId, 10);
        bad.put("seckillStart", Instant.now().plus(3, ChronoUnit.DAYS).toString());
        bad.put("seckillEnd", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        ResponseEntity<String> resp = post("/api/v1/admin/ticket-types", admin, bad);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(resp).path("code").asInt()).isEqualTo(2006);
    }

    @Test
    void ticketTypeUnderMissingEventShouldReturn2001() {
        String admin = createAdminToken();
        ResponseEntity<String> resp = post("/api/v1/admin/ticket-types", admin, ticketBody("123456789", 10));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(resp).path("code").asInt()).isEqualTo(2001);
    }

    @Test
    void getMissingEventShouldReturn2001() {
        String admin = createAdminToken();
        ResponseEntity<String> resp = get("/api/v1/admin/events/987654321", admin);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(resp).path("code").asInt()).isEqualTo(2001);
    }

    @Test
    void offlineTicketTypeCanBeUpdatedListedAndFetched() {
        String admin = createAdminToken();
        String eventId = json(post("/api/v1/admin/events", admin, eventBody("可編輯")))
                .path("data").path("id").asText();
        String ttId = json(post("/api/v1/admin/ticket-types", admin, ticketBody(eventId, 50)))
                .path("data").path("id").asText();

        // 更新(OFFLINE):改名、改價、改庫存 → stockRemaining 同步重設
        Map<String, Object> upd = ticketBody(eventId, 80);
        upd.put("name", "看台 B");
        upd.put("price", "1200.00");
        ResponseEntity<String> updated = put("/api/v1/admin/ticket-types/" + ttId, admin, upd);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode d = json(updated).path("data");
        assertThat(d.path("name").asText()).isEqualTo("看台 B");
        assertThat(d.path("totalStock").asInt()).isEqualTo(80);
        assertThat(d.path("stockRemaining").asInt()).isEqualTo(80);

        // get by id(JSON number,以數值比較,避免 double 文字化的尾零差異)
        assertThat(json(get("/api/v1/admin/ticket-types/" + ttId, admin))
                .path("data").path("price").asDouble()).isEqualTo(1200.00);

        // list by event
        JsonNode list = json(get("/api/v1/admin/ticket-types?eventId=" + eventId, admin)).path("data");
        assertThat(list.isArray()).isTrue();
        assertThat(list.get(0).path("id").asText()).isEqualTo(ttId);
    }

    @Test
    void warmupPastEventClampsTtlToMinimum() {
        String admin = createAdminToken();
        // 演出時間設在過去,warmup 算出的 TTL 應被 clamp 至保底值(不為負)
        Map<String, Object> past = eventBody("過去活動");
        past.put("eventTime", Instant.now().minus(5, ChronoUnit.DAYS).toString());
        String eventId = json(post("/api/v1/admin/events", admin, past)).path("data").path("id").asText();
        String ttId = json(post("/api/v1/admin/ticket-types", admin, ticketBody(eventId, 10)))
                .path("data").path("id").asText();

        ResponseEntity<String> resp = post("/api/v1/admin/ticket-types/" + ttId + "/warmup", admin, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(resp).path("data").path("redisStockRemaining").asInt()).isEqualTo(10);
    }

    @Test
    void warmupMissingTicketTypeShouldReturn2004() {
        String admin = createAdminToken();
        ResponseEntity<String> resp = post("/api/v1/admin/ticket-types/555666/warmup", admin, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(resp).path("code").asInt()).isEqualTo(2004);
    }

    @Test
    void onlineTicketTypeCannotBeEditedOrDeleted() {
        String admin = createAdminToken();
        String eventId = json(post("/api/v1/admin/events", admin, eventBody("上線後")))
                .path("data").path("id").asText();
        String ticketTypeId = json(post("/api/v1/admin/ticket-types", admin, ticketBody(eventId, 50)))
                .path("data").path("id").asText();

        // 上線
        assertThat(post("/api/v1/admin/ticket-types/" + ticketTypeId + "/warmup", admin, null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // 上線後更新 → 2005
        ResponseEntity<String> upd = put("/api/v1/admin/ticket-types/" + ticketTypeId, admin,
                ticketBody(eventId, 60));
        assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(upd).path("code").asInt()).isEqualTo(2005);

        // 上線後刪除 → 2005
        ResponseEntity<String> del = delete("/api/v1/admin/ticket-types/" + ticketTypeId, admin);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(del).path("code").asInt()).isEqualTo(2005);
    }
}
