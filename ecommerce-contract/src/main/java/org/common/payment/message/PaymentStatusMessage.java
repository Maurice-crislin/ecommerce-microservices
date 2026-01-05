package org.common.payment.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.common.payment.enums.PaymentStatus;

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
