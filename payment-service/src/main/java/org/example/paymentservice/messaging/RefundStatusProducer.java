package org.example.paymentservice.messaging;

import lombok.RequiredArgsConstructor;
import org.example.paymentservice.model.Refund;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefundStatusProducer {
    private final RabbitTemplate rabbitTemplate;
    public void sendRefundSuccess(Refund refund) {

        RefundStatusMessage refundStatusMessage = new RefundStatusMessage(
                refund.getRefundNo(),
                refund.getPaymentNo(),
                refund.getStatus()
        );

        rabbitTemplate.convertAndSend(RabbitMQConfig.REFUND_EXCHANGE,RabbitMQConfig.REFUND_SUCCESS_ROUTING_KEY, refundStatusMessage);
    }
    public void sendRefundFailure(Refund refund) {

        RefundStatusMessage refundStatusMessage = new RefundStatusMessage(
                refund.getRefundNo(),
                refund.getPaymentNo(),
                refund.getStatus()
        );
        rabbitTemplate.convertAndSend(RabbitMQConfig.REFUND_EXCHANGE,RabbitMQConfig.REFUND_FAILED_ROUTING_KEY, refundStatusMessage);
    }
}
