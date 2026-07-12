package com.seckill.order.mq;

import com.seckill.config.RabbitConfig;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 建單訊息生產者(設計文件第 7 節)。以 correlated publisher confirms + mandatory <b>同步</b>判定
 * 發送是否成功:須同時 broker ack 且非 unroutable(returned)才算成功。搶購熱路徑據此決定是否回補
 * Redis 庫存(見子項 F)。
 */
@Component
public class OrderMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderMessagePublisher.class);
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final RabbitTemplate rabbitTemplate;

    public OrderMessagePublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 發送建單訊息並等待 broker 確認。
     *
     * @return true 已確認送達並成功路由到佇列;false 未獲確認 / 逾時 / 無法路由(呼叫端應回補庫存)
     */
    public boolean publish(OrderMessage message) {
        CorrelationData correlation = new CorrelationData(message.requestId());
        rabbitTemplate.convertAndSend(
                RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_CREATE_ROUTING_KEY, message, correlation);
        try {
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck()) {
                log.error("MQ 未獲 broker 確認 requestId={} reason={}",
                        message.requestId(), confirm == null ? "timeout" : confirm.getReason());
                return false;
            }
            if (correlation.getReturned() != null) {
                // mandatory 下無法路由到任何佇列:視為失敗
                log.error("MQ 訊息無法路由 requestId={} replyText={}",
                        message.requestId(), correlation.getReturned().getReplyText());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("MQ 發送確認等待被中斷 requestId={}", message.requestId(), e);
            return false;
        } catch (ExecutionException | TimeoutException e) {
            log.error("MQ 發送確認失敗或逾時 requestId={}", message.requestId(), e);
            return false;
        }
    }
}
