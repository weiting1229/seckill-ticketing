package com.seckill.order.mq;

import com.rabbitmq.client.Channel;
import com.seckill.config.RabbitConfig;
import com.seckill.order.service.OrderCancelService;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 超時取消消費者(設計文件第 7 節)。消費 order.timeout.queue(延遲訊息到期後 dead-letter 至此),
 * 手動 ack;沿用建單消費者的重試 / DLQ 模式(見 {@link OrderCreateListener})。
 *
 * <ul>
 *   <li><b>正常</b>:呼叫 {@link OrderCancelService#cancelExpired}(冪等:僅 PENDING_PAYMENT 才取消回補)→ ack。
 *       訂單已 PAID / 已取消 → cancelExpired 回 false(no-op)→ ack。</li>
 *   <li><b>未預期例外</b>(如 DB 暫時不可用):依 {@code x-retry-count} header 即時重試,超過 3 次
 *       → nack(requeue=false)死信至 order.timeout.dlx → dlq。即便如此,兜底排程仍會兜底取消該訂單。</li>
 * </ul>
 */
@Component
public class OrderTimeoutListener {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutListener.class);
    private static final String RETRY_HEADER = "x-retry-count";
    private static final int MAX_RETRY = 3;

    private final OrderCancelService cancelService;
    private final RabbitTemplate rabbitTemplate;

    public OrderTimeoutListener(OrderCancelService cancelService, RabbitTemplate rabbitTemplate) {
        this.cancelService = cancelService;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = RabbitConfig.ORDER_TIMEOUT_QUEUE)
    public void onOrderTimeout(
            OrderDelayMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
            @Header(name = RETRY_HEADER, required = false) Integer retryCount) throws IOException {
        try {
            boolean cancelled = cancelService.cancelExpired(message.orderId());
            if (cancelled) {
                log.info("超時取消成功 orderId={} ticketTypeId={} userId={}",
                        message.orderId(), message.ticketTypeId(), message.userId());
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            handleUnexpected(message, channel, deliveryTag, retryCount, e);
        }
    }

    private void handleUnexpected(OrderDelayMessage message, Channel channel, long deliveryTag,
                                  Integer retryCount, Exception e) throws IOException {
        int retries = retryCount == null ? 0 : retryCount;
        if (retries < MAX_RETRY) {
            log.warn("超時取消失敗,第 {} 次重試 orderId={} err={}", retries + 1, message.orderId(), e.toString());
            republishWithRetry(message, retries + 1);
            channel.basicAck(deliveryTag, false); // 這則已改由重試訊息接手
        } else {
            log.error("超時取消重試耗盡({} 次),死信至 DLQ orderId={};兜底排程仍會兜底",
                    MAX_RETRY, message.orderId(), e);
            channel.basicNack(deliveryTag, false, false); // requeue=false → 走佇列 DLX → DLQ
        }
    }

    /** 以遞增的重試計數 header 重新入列超時佇列(header 計數,不依賴 requeue)。 */
    private void republishWithRetry(OrderDelayMessage message, int newRetryCount) {
        rabbitTemplate.convertAndSend(
                RabbitConfig.ORDER_TIMEOUT_EXCHANGE, RabbitConfig.ORDER_TIMEOUT_ROUTING_KEY, message,
                m -> {
                    m.getMessageProperties().setHeader(RETRY_HEADER, newRetryCount);
                    return m;
                });
    }
}
