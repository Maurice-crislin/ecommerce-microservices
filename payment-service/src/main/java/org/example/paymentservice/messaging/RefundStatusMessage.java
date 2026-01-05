package org.example.paymentservice.messaging;

import lombok.NonNull;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.common.payment.enums.RefundStatus;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class RefundStatusMessage implements Serializable {
    @NonNull
    private String refundNo;
    @NonNull
    private String paymentNo;
    @NonNull
    private RefundStatus status;
}

