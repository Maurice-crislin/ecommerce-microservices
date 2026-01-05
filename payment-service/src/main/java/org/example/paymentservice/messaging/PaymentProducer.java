package org.example.paymentservice.messaging;

import lombok.RequiredArgsConstructor;
import org.common.payment.message.PaymentStatusMessage;
import org.example.paymentservice.model.Payment;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentProducer {
    private final RabbitTemplate rabbitTemplate;
    public void sendPaymentStatusSuccess(Payment payment) {
        PaymentStatusMessage message = new PaymentStatusMessage(
                payment.getPaymentNo(),
                payment.getOrderId(),
                payment.getStatus()
        );
        rabbitTemplate.convertAndSend(RabbitMQConfig.PAYMENT_EXCHANGE,RabbitMQConfig.PAYMENT_SUCCESS_ROUTING_KEY, message);
    }
    public void sendPaymentStatusFailed(Payment payment) {
        PaymentStatusMessage message = new PaymentStatusMessage(
                payment.getPaymentNo(),
                payment.getOrderId(),
                payment.getStatus()
        );
        rabbitTemplate.convertAndSend(RabbitMQConfig.PAYMENT_EXCHANGE,RabbitMQConfig.PAYMENT_FAILED_ROUTING_KEY, message);
    }
}
