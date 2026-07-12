package com.seckill.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.seckill.config.RabbitConfig;
import com.seckill.order.mq.OrderMessage;
import com.seckill.order.mq.OrderMessagePublisher;
import com.seckill.support.AbstractIntegrationTest;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 建單訊息生產者(子項 D)整合測試:對真實 RabbitMQ(Testcontainers)驗證
 * publisher confirms + mandatory 的成功/無法路由判定。
 */
class OrderMessagePublisherIT extends AbstractIntegrationTest {

    @Autowired
    OrderMessagePublisher publisher;

    @Autowired
    RabbitTemplate rabbitTemplate;

    private OrderMessage sampleMessage() {
        return new OrderMessage(UUID.randomUUID().toString(), 1L, 2L, 3L, System.currentTimeMillis());
    }

    @Test
    void publishRoutableMessageReturnsTrue() {
        // 可路由(seckill.exchange + order.create → seckill.order.queue):acked 且非 returned
        assertThat(publisher.publish(sampleMessage())).isTrue();
    }

    @Test
    void unroutableMessageIsReturnedUnderMandatory() throws Exception {
        // 直接以不存在的 routing key 送出,驗證 mandatory + publisher-returns 已正確開啟:
        // broker 仍 ack,但訊息因無法路由被 return
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.convertAndSend(
                RabbitConfig.ORDER_EXCHANGE, "no.such.key", sampleMessage(), correlation);

        CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
        assertThat(confirm).isNotNull();
        assertThat(confirm.isAck()).as("broker 應 ack").isTrue();
        assertThat(correlation.getReturned()).as("無法路由應被 return").isNotNull();
    }
}
