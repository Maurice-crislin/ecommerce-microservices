package org.example.paymentservice.messaging;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.example.paymentservice.model.PaymentStatus;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
/* message event */
public class PaymentStatusMessage implements Serializable {
    @NonNull
    private String paymentNo;
    @NonNull
    private Long orderId;
    @NonNull
    private PaymentStatus status;
}
