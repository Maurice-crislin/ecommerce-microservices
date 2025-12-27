package org.example.paymentservice.messaging;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.example.paymentservice.model.PaymentStatus;

import java.io.Serializable;

@Data
@AllArgsConstructor
/* message event */
public class PaymentStatusMessage implements Serializable {
    private String paymentNo;
    private Long orderId;
    private PaymentStatus status;
    public PaymentStatusMessage() {
    }
}
