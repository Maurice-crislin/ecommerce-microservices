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

}
