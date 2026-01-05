package org.example.paymentservice.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String PAYMENT_EXCHANGE = "payment_exchange";
    public static final String PAYMENT_SUCCESS_STATUS_QUEUE = "payment_success_queue";
    public static final String PAYMENT_FAILED_STATUS_QUEUE = "payment_failed_queue";
    public static final String PAYMENT_SUCCESS_ROUTING_KEY = "payment_success_routing_key";
    public static final String PAYMENT_FAILED_ROUTING_KEY = "payment_failed_routing_key";

    @Bean
    public DirectExchange paymentExchange() {
        return new DirectExchange(PAYMENT_EXCHANGE);
    }


    @Bean
    public Queue paymentSuccessQueue() {
        return new Queue(PAYMENT_SUCCESS_STATUS_QUEUE);
    }

    @Bean
    public Queue paymentFailedQueue() {
        return new Queue(PAYMENT_FAILED_STATUS_QUEUE);
    }
    @Bean
    public Binding paymentStatusSuccessBinding() {
        return BindingBuilder
                .bind(paymentSuccessQueue())
                .to(paymentExchange())
                .with(PAYMENT_SUCCESS_ROUTING_KEY);

    }


    @Bean
    public Binding paymentStatusFailedBinding() {
        return BindingBuilder
                .bind(paymentFailedQueue())
                .to(paymentExchange())
                .with(PAYMENT_FAILED_ROUTING_KEY);

    }

    public static final String REFUND_EXCHANGE = "refund_exchange";
    public static final String REFUND_STATUS_QUEUE = "refund_status_queue";
    public static final String REFUND_SUCCESS_ROUTING_KEY = "refund_success_routing_key";
    public static final String REFUND_FAILED_ROUTING_KEY = "refund_failed_routing_key";

    @Bean
    public DirectExchange refundExchange() {
        return new DirectExchange(REFUND_EXCHANGE);
    }
    @Bean
    public Queue refundStatusQueue() {
        return new Queue(REFUND_STATUS_QUEUE, true);
    }

    @Bean
    public Binding refundStatusSuccessBinding() {
        return BindingBuilder
                .bind(refundStatusQueue())
                .to(refundExchange())
                .with(REFUND_SUCCESS_ROUTING_KEY);
    }

    @Bean
    public Binding refundStatusFailedBinding() {
        return BindingBuilder
                .bind(refundStatusQueue())
                .to(refundExchange())
                .with(REFUND_FAILED_ROUTING_KEY);
    }


    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);

        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();

        // 创建 TypeMapper 并设置受信包
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("org.example.paymentservice.messaging");
        converter.setJavaTypeMapper(typeMapper);

        template.setMessageConverter(converter);
        return template;
    }
}
