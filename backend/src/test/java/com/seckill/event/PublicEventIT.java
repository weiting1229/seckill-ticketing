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
 * 公開活動 API 整合測試:匿名可讀、分頁、詳情含票種與即時庫存、草稿不可見。
 */
class PublicEventIT extends AbstractAdminIntegrationTest {

    private String createEvent(String admin, String title) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        m.put("description", "公開描述");
        m.put("venue", "Taipei Dome");
        m.put("eventTime", Instant.now().plus(20, ChronoUnit.DAYS).toString());
        return json(post("/api/v1/admin/events", admin, m)).path("data").path("id").asText();
    }

    private String createEventWithCover(String admin, String title, String coverUrl) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        m.put("description", "公開描述");
        m.put("venue", "Taipei Dome");
        m.put("coverImageUrl", coverUrl);
        m.put("eventTime", Instant.now().plus(20, ChronoUnit.DAYS).toString());
        return json(post("/api/v1/admin/events", admin, m)).path("data").path("id").asText();
    }

    private void publish(String admin, String eventId, String title) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        m.put("description", "公開描述");
        m.put("venue", "Taipei Dome");
        m.put("eventTime", Instant.now().plus(20, ChronoUnit.DAYS).toString());
        m.put("status", "PUBLISHED");
        put("/api/v1/admin/events/" + eventId, admin, m);
    }

    private String createTicketType(String admin, String eventId, int total) {
        Map<String, Object> m = new HashMap<>();
        m.put("eventId", eventId);
        m.put("name", "VIP");
        m.put("price", "5000.00");
        m.put("totalStock", total);
        m.put("seckillStart", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        m.put("seckillEnd", Instant.now().plus(2, ChronoUnit.DAYS).toString());
        return json(post("/api/v1/admin/ticket-types", admin, m)).path("data").path("id").asText();
    }

    private boolean listContains(String eventId, int size) {
        int page = 1;
        while (true) {
            JsonNode data = json(get("/api/v1/events?page=" + page + "&size=" + size, null)).path("data");
            JsonNode items = data.path("items");
            for (JsonNode it : items) {
                if (it.path("id").asText().equals(eventId)) {
                    return true;
                }
            }
            long total = data.path("total").asLong();
            if ((long) page * size >= total || items.isEmpty()) {
                return false;
            }
            page++;
        }
    }

    @Test
    void publishedEventVisibleDraftHidden() {
        String admin = createAdminToken();
        String publishedId = createEvent(admin, "公開活動");
        publish(admin, publishedId, "公開活動");
        String draftId = createEvent(admin, "草稿活動");

        // 匿名可讀列表;已發布出現、草稿不出現
        ResponseEntity<String> list = get("/api/v1/events?page=1&size=50", null);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listContains(publishedId, 50)).isTrue();
        assertThat(listContains(draftId, 50)).isFalse();

        // 草稿詳情 → 404 2001(不洩漏未發布)
        ResponseEntity<String> draftDetail = get("/api/v1/events/" + draftId, null);
        assertThat(draftDetail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(draftDetail).path("code").asInt()).isEqualTo(2001);

        // 不存在 → 404 2001
        assertThat(get("/api/v1/events/111222333", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void detailIncludesTicketTypesServerTimeAndLiveStock() {
        String admin = createAdminToken();
        String eventId = createEvent(admin, "詳情活動");
        String ttId = createTicketType(admin, eventId, 300);
        publish(admin, eventId, "詳情活動");

        // 預熱前:remaining 為 null(尚未寫入 Redis)
        JsonNode before = json(get("/api/v1/events/" + eventId, null)).path("data");
        assertThat(before.path("serverTime").asText()).isNotBlank();
        JsonNode tt = before.path("ticketTypes").get(0);
        assertThat(tt.path("id").asText()).isEqualTo(ttId);
        assertThat(tt.path("remaining").isNull()).isTrue();
        assertThat(tt.path("status").asText()).isEqualTo("OFFLINE");

        // 預熱後:remaining 反映 Redis 現值
        post("/api/v1/admin/ticket-types/" + ttId + "/warmup", admin, null);
        JsonNode after = json(get("/api/v1/events/" + eventId, null)).path("data");
        JsonNode ttAfter = after.path("ticketTypes").get(0);
        assertThat(ttAfter.path("remaining").asInt()).isEqualTo(300);
        assertThat(ttAfter.path("status").asText()).isEqualTo("ONLINE");
    }

    @Test
    void publicListAndDetailCarryCoverImageUrl() {
        String admin = createAdminToken();
        String coverUrl = "https://cdn.example.com/cover.png";
        String eventId = createEventWithCover(admin, "封面公開活動", coverUrl);

        // 發布時於全量入參保留封面(PUT 全量,不帶會被清空)
        Map<String, Object> pub = new HashMap<>();
        pub.put("title", "封面公開活動");
        pub.put("description", "公開描述");
        pub.put("venue", "Taipei Dome");
        pub.put("coverImageUrl", coverUrl);
        pub.put("eventTime", Instant.now().plus(20, ChronoUnit.DAYS).toString());
        pub.put("status", "PUBLISHED");
        put("/api/v1/admin/events/" + eventId, admin, pub);

        // 公開詳情帶出封面
        assertThat(json(get("/api/v1/events/" + eventId, null))
                .path("data").path("coverImageUrl").asText()).isEqualTo(coverUrl);

        // 公開列表對應項帶出封面
        boolean found = false;
        JsonNode items = json(get("/api/v1/events?page=1&size=50", null)).path("data").path("items");
        for (JsonNode it : items) {
            if (it.path("id").asText().equals(eventId)) {
                assertThat(it.path("coverImageUrl").asText()).isEqualTo(coverUrl);
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void listFiltersByKeywordAndPaginatesFilteredResult() {
        String admin = createAdminToken();
        // 唯一 ASCII 前綴,避免與其他測試資料干擾、也避開查詢字串編碼問題
        String tag = "kw" + System.nanoTime();
        String a1 = createEvent(admin, tag + "MAYDAY");
        publish(admin, a1, tag + "MAYDAY");
        String a2 = createEvent(admin, tag + "MAYDAYENCORE");
        publish(admin, a2, tag + "MAYDAYENCORE");
        String b = createEvent(admin, tag + "GAOWUREN");
        publish(admin, b, tag + "GAOWUREN");

        // keyword 用小寫,驗證 ILIKE 大小寫不敏感;子字串命中 a1、a2 不含 b
        String kw = tag + "mayday";
        JsonNode data = json(get("/api/v1/events?page=1&size=50&keyword=" + kw, null)).path("data");
        assertThat(data.path("total").asLong()).isEqualTo(2);
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (JsonNode it : data.path("items")) {
            ids.add(it.path("id").asText());
        }
        assertThat(ids).containsExactlyInAnyOrder(a1, a2);
        assertThat(ids).doesNotContain(b);

        // 過濾後分頁:size=1 → 每頁一筆、total 仍為 2
        JsonNode paged = json(get("/api/v1/events?page=1&size=1&keyword=" + kw, null)).path("data");
        assertThat(paged.path("items").size()).isEqualTo(1);
        assertThat(paged.path("total").asLong()).isEqualTo(2);

        // 空 keyword 視為不過濾(不因空字串回零筆)
        JsonNode noKw = json(get("/api/v1/events?page=1&size=50&keyword=", null)).path("data");
        assertThat(noKw.path("total").asLong()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void listRespectsPageSize() {
        String admin = createAdminToken();
        // 確保至少有兩筆已發布
        for (int i = 0; i < 2; i++) {
            String id = createEvent(admin, "分頁-" + i);
            publish(admin, id, "分頁-" + i);
        }
        JsonNode data = json(get("/api/v1/events?page=1&size=1", null)).path("data");
        assertThat(data.path("page").asInt()).isEqualTo(1);
        assertThat(data.path("size").asInt()).isEqualTo(1);
        assertThat(data.path("items").size()).isEqualTo(1);
        assertThat(data.path("total").asLong()).isGreaterThanOrEqualTo(2);
    }
}
