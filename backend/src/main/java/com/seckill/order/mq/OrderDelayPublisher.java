package com.seckill.order.mq;

import com.seckill.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 延遲取消訊息生產者(設計文件第 7 節)。建單消費者落庫成功後發一則延遲訊息;訊息在 order.delay.queue
 * 滯留至 {@code x-message-ttl}(預設 15 分鐘)到期後,dead-letter 觸發超時取消。
 *
 * <p><b>最佳努力(best-effort)語意</b>:此處<b>不</b>做同步 confirm 阻塞。訂單已落庫,延遲訊息即使
 * 偶發遺失,亦由<b>兜底排程</b>(子項 5)掃描過期 PENDING 訂單作為第二保險(belt-and-suspenders,
 * 見 ADR 0005)。故發送例外僅記結構化日誌,不向上拋、不影響建單訊息的 ack。
 */
@Component
public class OrderDelayPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderDelayPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public OrderDelayPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** 發送延遲取消訊息(使用佇列層 x-message-ttl);失敗僅記日誌,由兜底排程兜底。 */
    public void publish(OrderDelayMessage message) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitConfig.ORDER_DELAY_EXCHANGE, RabbitConfig.ORDER_DELAY_ROUTING_KEY, message);
        } catch (AmqpException e) {
            log.error("延遲取消訊息發送失敗 orderId={};將由兜底排程兜底", message.orderId(), e);
        }
    }
}
