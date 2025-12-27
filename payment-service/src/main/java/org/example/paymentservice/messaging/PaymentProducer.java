package org.example.paymentservice.messaging;

import lombok.RequiredArgsConstructor;
import org.example.paymentservice.model.Payment;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentProducer {
    private final RabbitTemplate rabbitTemplate;
    public void sendPaymentStatus(Payment payment) {
        PaymentStatusMessage message = new PaymentStatusMessage(
                payment.getPaymentNo(),
                payment.getOrderId(),
                payment.getStatus()
        );
        // todo
        rabbitTemplate.convertAndSend(RabbitMQConfig.PAYMENT_EXCHANGE,RabbitMQConfig.PAYMENT_STATUS_ROUTING_KEY, message);
    }
}
