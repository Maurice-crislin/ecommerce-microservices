package org.example.inventoryservice.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Configuration
public class RabbitMQConfig {


    public static final String INVENTORY_UNLOCK_EXCHANGE = "inventory_unlock_exchange";
    public static final String INVENTORY_UNLOCK_QUEUE = "inventory_unlock_queue";
    public static final String INVENTORY_UNLOCK_ROUTING_KEY = "inventory_unlock_routing_key";


    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter(objectMapper));
        return template;
    }


    @Bean
    public  DirectExchange inventoryUnlockExchange(){
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

