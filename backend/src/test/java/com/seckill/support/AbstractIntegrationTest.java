package com.seckill.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * 整合測試共用基底。
 *
 * <p>採 <b>singleton container</b> 模式:容器在 static 區塊啟動一次、跨所有整合測試類別共用,
 * 到 JVM 結束時由 Testcontainers Ryuk 清理。刻意<b>不</b>用 {@code @Testcontainers} 管理生命週期——
 * 否則每個測試類別的 afterAll 都會停掉這組共用容器,導致後續類別沿用快取 context 卻連到已停止的容器。
 *
 * <p>連線資訊與測試用 JWT 祕密皆以 {@link DynamicPropertySource} 注入,不依賴外部環境變數。
 * 需要 Docker;無 Docker 環境執行整合測試會直接失敗(設計文件要求整合測試一律 Testcontainers,禁 H2)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    /** 測試用 JWT 祕密(≥ 256 bit);AuthFlowIT 亦以此簽發過期/篡改 token。 */
    public static final String TEST_JWT_SECRET =
            "test-only-jwt-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    static {
        POSTGRES.start();
        REDIS.start();
        RABBITMQ.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("seckill.jwt.secret", () -> TEST_JWT_SECRET);
    }
}
