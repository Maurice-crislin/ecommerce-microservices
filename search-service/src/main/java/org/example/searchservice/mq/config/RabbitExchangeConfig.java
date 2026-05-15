package org.example.searchservice.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.common.mq.constants.ProductMQConstants.*;

@Configuration
public class RabbitExchangeConfig {


    @Bean
    public DirectExchange directExchange() {
        return new DirectExchange(EXCHANGE_NAME);
    }
    @Bean
    public Binding createdBinding(Queue createdQueue, DirectExchange directExchange) {
        return BindingBuilder.bind(createdQueue).to(directExchange).with(CREATED_ROUTE_KEY);
    }
    @Bean
    public Binding updatedBinding(Queue updatedQueue, DirectExchange directExchange) {
        return BindingBuilder.bind(updatedQueue).to(directExchange).with(UPDATED_ROUTE_KEY);
    }
    @Bean
    public Binding deletedBinding(Queue deletedQueue, DirectExchange directExchange) {
        return BindingBuilder.bind(deletedQueue).to(directExchange).with(DELETED_ROUTE_KEY);
    }
}
