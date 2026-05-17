package org.example.inventoryservice.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Configuration
public class RabbitMQConfig {


    public static final String INVENTORY_UNLOCK_EXCHANGE = "inventory_unlock_exchange";
    public static final String INVENTORY_UNLOCK_QUEUE = "inventory_unlock_queue";
    public static final String INVENTORY_UNLOCK_ROUTING_KEY = "inventory_unlock_routing_key";


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


    // ======================
    // RabbitTemplate (Producer)
    // ======================
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);

        // create converter
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();

        // create TypeMapper and setTrustedPackages
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        // 匹配 dto 包及其子包（Spring AMQP 底层是 startsWith 判断）
        // typeMapper.setTrustedPackages("org.common.inventory.dto");
        typeMapper.setTrustedPackages("*");

        converter.setJavaTypeMapper(typeMapper);
        template.setMessageConverter(converter);

        return template;
    }

    // ======================
    // RabbitListener (Consumer)
    // ======================
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory
    ) {
        Jackson2JsonMessageConverter jacksonMessageConverter = new Jackson2JsonMessageConverter();

        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();

        factory.setConnectionFactory(connectionFactory);

        // 关键：给 Listener 指定 Jackson Converter（同样需要配置 trustedPackages）
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        // typeMapper.setTrustedPackages("org.common.*");
        typeMapper.setTrustedPackages("*");
        jacksonMessageConverter.setJavaTypeMapper(typeMapper);

        factory.setMessageConverter(jacksonMessageConverter);

        // 可选但推荐
        factory.setDefaultRequeueRejected(false);

        return factory;
    }

}