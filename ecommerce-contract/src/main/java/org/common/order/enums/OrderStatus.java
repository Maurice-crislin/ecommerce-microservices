package org.common.order.enums;

/**
 * PROCESSING
 *     ├──> PAID
 *     ├──> FAILED
 *     ├──> TIMEOUT
 *     └──> CANCELED
 * */
public enum OrderStatus {
    PROCESSING,
    AWAITING_PAYMENT,
    PAID,
    FAILED,
    CANCELED,
    TIMEOUT
}

