package org.example.orderservice.messaging;

import com.rabbitmq.client.AMQP;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMQConfig {
    public static final String ORDER_DELAY_EXCHANGE = "order_delay_exchange";
    public static final String ORDER_DELAY_QUEUE = "order_delay_queue";
    public static final String ORDER_DELAY_ROUTING_KEY = "order_delay_routing_key";


    @Bean
    public DirectExchange orderDelayExchange(){
        return new DirectExchange(ORDER_DELAY_EXCHANGE);
    }

    @Bean
    public Queue orderDelayQueue(){
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", ORDER_RELEASE_EXCHANGE);
        args.put("x-dead-letter-routing-key", ORDER_RELEASE_ROUTING_KEY);
        args.put("x-message-ttl", 30 * 60 * 1000); // 30mins
        return new Queue(ORDER_DELAY_QUEUE,true,false,false,args);
    }

    @Bean
    public Binding orderDelayBinding(){
        return BindingBuilder
                .bind(orderDelayQueue())
                .to(orderDelayExchange())
                .with(ORDER_DELAY_ROUTING_KEY);
    }


    public static final String ORDER_RELEASE_EXCHANGE = "order_release_exchange";
    public static final String ORDER_RELEASE_QUEUE = "order_release_queue";
    public static final String ORDER_RELEASE_ROUTING_KEY = "order_release_routing_key";


    @Bean
    public DirectExchange orderReleaseExchange(){
        return new DirectExchange(ORDER_RELEASE_EXCHANGE);
    }
    @Bean
    public Queue orderReleaseQueue(){
        return new Queue(ORDER_RELEASE_QUEUE);
    }

    @Bean
    public Binding orderReleaseBinding(){
        return BindingBuilder
                .bind(orderReleaseQueue())
                .to(orderReleaseExchange())
                .with(ORDER_RELEASE_ROUTING_KEY);
    }

    public static final String INVENTORY_UNLOCK_EXCHANGE = "inventory_unlock_exchange";
    public static final String INVENTORY_UNLOCK_QUEUE = "inventory_unlock_queue";
    public static final String INVENTORY_UNLOCK_ROUTING_KEY = "inventory_unlock_routing_key";
    @Bean
    public DirectExchange inventoryUnlockExchange(){
        return new DirectExchange(INVENTORY_UNLOCK_EXCHANGE);
    }
    @Bean
    public Queue inventoryUnlockQueue(){
        return new Queue(INVENTORY_UNLOCK_QUEUE);
    }
    @Bean
    public Binding inventoryUnlockBinding(){
        return BindingBuilder
                .bind(inventoryUnlockQueue())
                .to(inventoryUnlockExchange())
                .with(INVENTORY_UNLOCK_ROUTING_KEY);
    }

    public static final String INVENTORY_CONFIRM_EXCHANGE = "inventory_confirm_exchange";
    public static final String INVENTORY_CONFIRM_QUEUE = "inventory_confirm_queue";
    public static final String INVENTORY_CONFIRM_ROUTING_KEY = "inventory_confirm_routing_key";

    @Bean
    public  DirectExchange inventoryConfirmExchange(){
        return new DirectExchange(INVENTORY_CONFIRM_EXCHANGE);
    }
    @Bean
    public Queue inventoryConfirmQueue(){
        return new Queue(INVENTORY_CONFIRM_QUEUE);
    }
    @Bean
    public Binding inventoryConfirmBinding(){
        return BindingBuilder
                .bind(inventoryConfirmQueue())
                .to(inventoryConfirmExchange())
                .with(INVENTORY_CONFIRM_ROUTING_KEY);
    }

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

    public static final String REFUND_REQUEST_EXCHANGE = "refund_request_exchange";
    public static final String REFUND_REQUEST_QUEUE = "refund_request_queue";
    public static final String REFUND_REQUEST_ROUTING_KEY = "refund_request_routing_key";

    @Bean
    public DirectExchange refundRequestExchange() {
        return new DirectExchange(REFUND_REQUEST_EXCHANGE);
    }
    @Bean
    public Queue refundRequestQueue() {
        return new Queue(REFUND_REQUEST_QUEUE, true);
    }
    @Bean
    public Binding refundRequestBinding() {
        return BindingBuilder
                .bind(refundRequestQueue())
                .to(refundRequestExchange())
                .with(REFUND_REQUEST_ROUTING_KEY);
    }

}
