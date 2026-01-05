package org.example.orderservice.messaging;

import lombok.RequiredArgsConstructor;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderMessageProducer {
    private final RabbitTemplate rabbitTemplate;
    public void sendOrderTimeoutDelayMessage(Long orderId){
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_DELAY_EXCHANGE,
                RabbitMQConfig.ORDER_DELAY_ROUTING_KEY,
                orderId
        );
    }
}
