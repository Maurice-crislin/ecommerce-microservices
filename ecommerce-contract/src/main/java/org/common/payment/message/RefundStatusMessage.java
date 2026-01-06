package org.common.payment.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.common.payment.enums.RefundStatus;

import java.io.Serializable;


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

