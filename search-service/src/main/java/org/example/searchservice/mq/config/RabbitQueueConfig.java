package org.example.searchservice.mq.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitQueueConfig {
    @Bean
    public Queue createdQueue(){
        return new Queue("product-created");
    }
    @Bean
    public Queue updatedQueue(){
        return new Queue("product-updated");
    }
    @Bean
    public Queue deletedQueue(){
        return new Queue("product-deleted");
    }
}
