package com.seckill.order.mq;

import com.rabbitmq.client.Channel;
import com.seckill.common.metrics.SeckillMetrics;
import com.seckill.config.RabbitConfig;
import com.seckill.event.service.StockCache;
import com.seckill.order.service.DbStockDepletedException;
import com.seckill.order.service.OrderCreateService;
import com.seckill.order.service.OrderResultCache;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 建單消費者(設計文件第 7 節):手動 ack、消費併發 4(application.yml)。
 *
 * <ul>
 *   <li><b>成功</b>:落庫 → 寫 {@code SUCCESS:orderId} → ack。</li>
 *   <li><b>冪等</b>:唯一鍵衝突(DuplicateKeyException)視為已處理 → 補寫 SUCCESS → ack。</li>
 *   <li><b>DB 售罄</b>(異常訊號):回補 Redis 庫存 → 寫 {@code FAIL} → ack(不重試)。</li>
 *   <li><b>未預期例外</b>:依 {@code x-retry-count} header 重試,超過 3 次 → nack(requeue=false)
 *       死信至 DLQ,人工介入。</li>
 * </ul>
 */
@Component
public class OrderCreateListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCreateListener.class);
    private static final String RETRY_HEADER = "x-retry-count";
    private static final int MAX_RETRY = 3;

    private final OrderCreateService orderCreateService;
    private final OrderResultCache resultCache;
    private final StockCache stockCache;
    private final RabbitTemplate rabbitTemplate;
    private final SeckillMetrics metrics;

    public OrderCreateListener(OrderCreateService orderCreateService, OrderResultCache resultCache,
                               StockCache stockCache, RabbitTemplate rabbitTemplate, SeckillMetrics metrics) {
        this.orderCreateService = orderCreateService;
        this.resultCache = resultCache;
        this.stockCache = stockCache;
        this.rabbitTemplate = rabbitTemplate;
        this.metrics = metrics;
    }

    @RabbitListener(queues = RabbitConfig.ORDER_QUEUE)
    public void onOrderCreate(
            OrderMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
            @Header(name = RETRY_HEADER, required = false) Integer retryCount) throws IOException {
        try {
            orderCreateService.createOrder(message);
            resultCache.writeSuccess(message.requestId(), message.orderId());
            // 從發 MQ(訊息 timestamp)到落庫的耗時
            metrics.recordOrderCreateDuration(
                    String.valueOf(message.ticketTypeId()),
                    System.currentTimeMillis() - message.timestamp());
            channel.basicAck(deliveryTag, false);
        } catch (DuplicateKeyException e) {
            // 冪等:此 requestId(或該 user+票種)已建過訂單,直接視為已處理
            resultCache.writeSuccess(message.requestId(), message.orderId());
            channel.basicAck(deliveryTag, false);
        } catch (DbStockDepletedException e) {
            // DB 扣減失敗(異常訊號):回補 Redis 並標記 FAIL,不重試
            stockCache.revert(message.ticketTypeId(), message.userId());
            metrics.recordStockRevert(String.valueOf(message.ticketTypeId()));
            resultCache.writeFail(message.requestId(), "STOCK_DEPLETED");
            log.error("建單時 DB 售罄,已回補 Redis 並標記 FAIL requestId={}", message.requestId(), e);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            handleUnexpected(message, channel, deliveryTag, retryCount, e);
        }
    }

    private void handleUnexpected(OrderMessage message, Channel channel, long deliveryTag,
                                  Integer retryCount, Exception e) throws IOException {
        int retries = retryCount == null ? 0 : retryCount;
        if (retries < MAX_RETRY) {
            log.warn("建單失敗,第 {} 次重試 requestId={} err={}", retries + 1, message.requestId(), e.toString());
            republishWithRetry(message, retries + 1);
            channel.basicAck(deliveryTag, false); // 目前這則已改由重試訊息接手
        } else {
            log.error("建單重試耗盡({} 次),死信至 DLQ requestId={}", MAX_RETRY, message.requestId(), e);
            channel.basicNack(deliveryTag, false, false); // requeue=false → 走佇列 DLX → DLQ
        }
    }

    /** 以遞增的重試計數 header 重新入列(header 計數,不依賴 requeue)。 */
    private void republishWithRetry(OrderMessage message, int newRetryCount) {
        rabbitTemplate.convertAndSend(
                RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_CREATE_ROUTING_KEY, message,
                m -> {
                    m.getMessageProperties().setHeader(RETRY_HEADER, newRetryCount);
                    return m;
                });
    }
}
