package com.seckill.order.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.rabbitmq.client.Channel;
import com.seckill.common.metrics.SeckillMetrics;
import com.seckill.config.RabbitConfig;
import com.seckill.event.service.StockCache;
import com.seckill.order.service.OrderCreateService;
import com.seckill.order.service.OrderResultCache;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * 冪等分支(DuplicateKeyException)裡 resultCache.writeSuccess 失敗時的行為。
 *
 * <p>該呼叫若拋例外,不應繞過 handleUnexpected 的重試/死信保護機制
 * (真實案例:2026-08-16 壓測導致 Redis 暫時連不上,訊息卡住 18 分鐘以上未死信)。
 */
class OrderCreateListenerTest {

    private static final String RETRY_HEADER = "x-retry-count";
    private static final int MAX_RETRY = 3;

    private final OrderCreateService orderCreateService = mock(OrderCreateService.class);
    private final OrderResultCache resultCache = mock(OrderResultCache.class);
    private final StockCache stockCache = mock(StockCache.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final SeckillMetrics metrics = mock(SeckillMetrics.class);
    private final OrderDelayPublisher orderDelayPublisher = mock(OrderDelayPublisher.class);
    private final Channel channel = mock(Channel.class);

    private final OrderCreateListener listener = new OrderCreateListener(
            orderCreateService, resultCache, stockCache, rabbitTemplate, metrics, orderDelayPublisher);

    private static final long DELIVERY_TAG = 1L;
    private final OrderMessage message = new OrderMessage("req-1", 10L, 20L, 30L, System.currentTimeMillis());

    @Test
    void duplicateKeyWithWriteSuccessFailureRetriesInsteadOfEscaping() throws Exception {
        doThrow(new DuplicateKeyException("dup")).when(orderCreateService).createOrder(message);
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(resultCache).writeSuccess(message.requestId(), message.orderId());

        assertThatCode(() -> listener.onOrderCreate(message, channel, DELIVERY_TAG, null))
                .doesNotThrowAnyException();

        ArgumentCaptor<MessagePostProcessor> processorCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.ORDER_EXCHANGE), eq(RabbitConfig.ORDER_CREATE_ROUTING_KEY),
                eq(message), processorCaptor.capture());
        Message republished = processorCaptor.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        Object retryHeaderValue = republished.getMessageProperties().getHeader(RETRY_HEADER);
        assertThat(retryHeaderValue).isEqualTo(1);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(orderDelayPublisher, never()).publish(any());
    }

    @Test
    void duplicateKeyWithWriteSuccessFailureDeadLettersAfterMaxRetry() throws Exception {
        doThrow(new DuplicateKeyException("dup")).when(orderCreateService).createOrder(message);
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(resultCache).writeSuccess(message.requestId(), message.orderId());

        assertThatCode(() -> listener.onOrderCreate(message, channel, DELIVERY_TAG, MAX_RETRY))
                .doesNotThrowAnyException();

        verify(rabbitTemplate, never()).convertAndSend(
                anyString(), anyString(), any(OrderMessage.class), any(MessagePostProcessor.class));
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void duplicateKeyWithWriteSuccessOkStillAcksWithoutRetry() throws Exception {
        doThrow(new DuplicateKeyException("dup")).when(orderCreateService).createOrder(message);

        listener.onOrderCreate(message, channel, DELIVERY_TAG, null);

        verify(resultCache).writeSuccess(message.requestId(), message.orderId());
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(rabbitTemplate, never()).convertAndSend(
                anyString(), anyString(), any(OrderMessage.class), any(MessagePostProcessor.class));
        verify(orderDelayPublisher, never()).publish(any());
    }
}
