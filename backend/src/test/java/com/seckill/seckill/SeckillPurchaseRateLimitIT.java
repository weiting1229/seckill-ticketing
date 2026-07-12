package com.seckill.seckill;

import static org.assertj.core.api.Assertions.assertThat;

import com.seckill.support.AbstractAdminIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 搶購限流 429 端到端(子項 F 補上子項 C 未測的 HTTP 路徑)。以預設閾值,單一帳號快打 purchase,
 * 應觸發攔截器回 429(單用戶 2/s;fresh user 為 key,不與其他測試互擾)。
 */
class SeckillPurchaseRateLimitIT extends AbstractAdminIntegrationTest {

    @Test
    void purchaseRateLimitedPerUser() {
        String user = createUserToken();

        // 連打 8 次(遠超單用戶 2/s);token 給假值,但限流在 controller 前先攔截
        boolean saw429 = false;
        for (int i = 0; i < 8; i++) {
            HttpStatus status = (HttpStatus) post("/api/v1/seckill/purchase", user,
                    Map.of("ticketTypeId", 1L, "token", "dummy")).getStatusCode();
            if (status == HttpStatus.TOO_MANY_REQUESTS) {
                saw429 = true;
            }
        }
        assertThat(saw429).as("單用戶快打 purchase 應觸發 429").isTrue();
    }
}
