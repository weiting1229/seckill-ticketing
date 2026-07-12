package com.seckill.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 建單拓撲(設計文件第 7 節)。M3 只宣告<b>建單</b>相關;延遲取消(order.delay.* / timeout)
 * 屬訂單生命週期 M4,屆時再宣告並接消費者(見 ADR 0004 的 M3/M4 邊界)。
 *
 * <pre>
 * seckill.exchange (direct)
 *   └─ order.create ─▶ seckill.order.queue         建單佇列(手動 ack、消費併發 4;M3 子項 E)
 *         x-dead-letter ─▶ seckill.order.dlx ─▶ seckill.order.dlq   消費重試 3 次後進死信,人工介入
 * </pre>
 *
 * <p>訊息以 JSON 傳遞({@link Jackson2JsonMessageConverter});生產端 publisher confirms + mandatory
 * 於 application.yml 開啟,發送失敗回補 Redis(見 OrderMessagePublisher)。
 */
@Configuration
public class RabbitConfig {

    public static final String ORDER_EXCHANGE = "seckill.exchange";
    public static final String ORDER_CREATE_ROUTING_KEY = "order.create";
    public static final String ORDER_QUEUE = "seckill.order.queue";

    public static final String ORDER_DLX = "seckill.order.dlx";
    public static final String ORDER_DLQ = "seckill.order.dlq";
    public static final String ORDER_DLQ_ROUTING_KEY = "order.create.dead";

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

    /**
     * JSON 訊息轉換;生產/消費共用。Boot 會自動注入至 RabbitTemplate 與監聽容器。
     * 以 __TypeId__ header 還原型別時,僅信任本專案訊息套件(安全預設,避免反序列化任意類別)。
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
