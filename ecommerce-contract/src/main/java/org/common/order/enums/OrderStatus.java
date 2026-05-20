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
    PAID,
    FAILED,
    CANCELED,
    TIMEOUT
}

