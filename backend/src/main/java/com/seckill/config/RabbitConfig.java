package com.seckill.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓撲(設計文件第 7 節)。含 M3 建單拓撲與 M4 延遲取消拓撲。
 *
 * <pre>
 * 建單(M3):
 * seckill.exchange (direct)
 *   └─ order.create ─▶ seckill.order.queue         建單佇列(手動 ack、消費併發 4;M3 子項 E)
 *         x-dead-letter ─▶ seckill.order.dlx ─▶ seckill.order.dlq   消費重試 3 次後進死信,人工介入
 *
 * 延遲取消(M4):
 * order.delay.exchange (direct)
 *   └─ order.delay ─▶ order.delay.queue            無消費者;x-message-ttl(預設 15 分鐘)
 *         x-dead-letter ─▶ order.timeout.exchange ─▶ order.timeout.queue   到期後路由至此
 *               └─ consumer:仍 PENDING_PAYMENT 則取消 + 回補(OrderTimeoutListener)
 *                     x-dead-letter ─▶ order.timeout.dlx ─▶ order.timeout.dlq   取消重試耗盡進死信
 * </pre>
 *
 * <p>訊息以 JSON 傳遞({@link Jackson2JsonMessageConverter});生產端 publisher confirms + mandatory
 * 於 application.yml 開啟,發送失敗回補 Redis(見 OrderMessagePublisher)。
 *
 * <p><b>延遲 TTL 與可測試性</b>:{@code order.delay.queue} 的 {@code x-message-ttl} 由
 * {@code order.delay.ttl-ms} 決定(預設 900000=15 分鐘),<b>所有執行環境一致</b>(正式可經 env 調整)。
 * 測試提速<b>不</b>覆寫佇列 TTL(否則同名 durable 佇列以不同參數重複宣告會 PRECONDITION_FAILED,
 * 且跨 cached context 競爭消費不確定),改以逐訊息 {@code expiration}(RabbitMQ 取 queue 與 per-message
 * TTL 的最小值)在測試端直接發短延遲訊息(見 ADR 0005)。
 */
@Configuration
public class RabbitConfig {

    public static final String ORDER_EXCHANGE = "seckill.exchange";
    public static final String ORDER_CREATE_ROUTING_KEY = "order.create";
    public static final String ORDER_QUEUE = "seckill.order.queue";

    public static final String ORDER_DLX = "seckill.order.dlx";
    public static final String ORDER_DLQ = "seckill.order.dlq";
    public static final String ORDER_DLQ_ROUTING_KEY = "order.create.dead";

    // --- M4 延遲取消拓撲 ---
    public static final String ORDER_DELAY_EXCHANGE = "order.delay.exchange";
    public static final String ORDER_DELAY_ROUTING_KEY = "order.delay";
    public static final String ORDER_DELAY_QUEUE = "order.delay.queue";

    public static final String ORDER_TIMEOUT_EXCHANGE = "order.timeout.exchange";
    public static final String ORDER_TIMEOUT_ROUTING_KEY = "order.timeout";
    public static final String ORDER_TIMEOUT_QUEUE = "order.timeout.queue";

    public static final String ORDER_TIMEOUT_DLX = "order.timeout.dlx";
    public static final String ORDER_TIMEOUT_DLQ = "order.timeout.dlq";
    public static final String ORDER_TIMEOUT_DLQ_ROUTING_KEY = "order.timeout.dead";

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange orderDlx() {
        return new DirectExchange(ORDER_DLX, true, false);
    }

    /** 建單佇列;消費失敗達重試上限即死信至 DLX → DLQ。 */
    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable(ORDER_QUEUE)
                .withArgument("x-dead-letter-exchange", ORDER_DLX)
                .withArgument("x-dead-letter-routing-key", ORDER_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue orderDlq() {
        return QueueBuilder.durable(ORDER_DLQ).build();
    }

    @Bean
    public Binding orderQueueBinding() {
        return BindingBuilder.bind(orderQueue()).to(orderExchange()).with(ORDER_CREATE_ROUTING_KEY);
    }

    @Bean
    public Binding orderDlqBinding() {
        return BindingBuilder.bind(orderDlq()).to(orderDlx()).with(ORDER_DLQ_ROUTING_KEY);
    }

    // ---- M4 延遲取消拓撲 ----

    @Bean
    public DirectExchange orderDelayExchange() {
        return new DirectExchange(ORDER_DELAY_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange orderTimeoutExchange() {
        return new DirectExchange(ORDER_TIMEOUT_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange orderTimeoutDlx() {
        return new DirectExchange(ORDER_TIMEOUT_DLX, true, false);
    }

    /**
     * 延遲佇列:<b>無消費者</b>。訊息在此滯留 {@code x-message-ttl}(預設 15 分鐘)後到期,
     * 經 dead-letter 路由至 order.timeout.exchange → order.timeout.queue 觸發超時取消。
     */
    @Bean
    public Queue orderDelayQueue(@Value("${order.delay.ttl-ms:900000}") int delayTtlMs) {
        return QueueBuilder.durable(ORDER_DELAY_QUEUE)
                .ttl(delayTtlMs)
                .deadLetterExchange(ORDER_TIMEOUT_EXCHANGE)
                .deadLetterRoutingKey(ORDER_TIMEOUT_ROUTING_KEY)
                .build();
    }

    /** 超時佇列:<b>有消費者</b>(OrderTimeoutListener);取消重試耗盡即死信至 order.timeout.dlx → dlq。 */
    @Bean
    public Queue orderTimeoutQueue() {
        return QueueBuilder.durable(ORDER_TIMEOUT_QUEUE)
                .deadLetterExchange(ORDER_TIMEOUT_DLX)
                .deadLetterRoutingKey(ORDER_TIMEOUT_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue orderTimeoutDlq() {
        return QueueBuilder.durable(ORDER_TIMEOUT_DLQ).build();
    }

    @Bean
    public Binding orderDelayQueueBinding(Queue orderDelayQueue) {
        return BindingBuilder.bind(orderDelayQueue).to(orderDelayExchange()).with(ORDER_DELAY_ROUTING_KEY);
    }

    @Bean
    public Binding orderTimeoutQueueBinding() {
        return BindingBuilder.bind(orderTimeoutQueue()).to(orderTimeoutExchange()).with(ORDER_TIMEOUT_ROUTING_KEY);
    }

    @Bean
    public Binding orderTimeoutDlqBinding() {
        return BindingBuilder.bind(orderTimeoutDlq()).to(orderTimeoutDlx()).with(ORDER_TIMEOUT_DLQ_ROUTING_KEY);
    }

    /**
     * JSON 訊息轉換;生產/消費共用。Boot 會自動注入至 RabbitTemplate 與監聽容器。
     * 以 __TypeId__ header 還原型別時,僅信任本專案訊息套件(安全預設,避免反序列化任意類別)。
     * 建單({@code OrderMessage})與延遲({@code OrderDelayMessage})訊息同屬 com.seckill.order.mq。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("com.seckill.order.mq");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }
}
