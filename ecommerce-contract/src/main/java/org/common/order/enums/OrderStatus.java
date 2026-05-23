package org.common.order.enums;

/**
 * PROCESSING
 *     -> AWAITING_PAYMENT ->PAYING
 *                                 ├──> PAID
 *                                 ├──> FAILED
 *     -> AWAITING_PAYMENT
 *     ├──> TIMEOUT
 *     └──> CANCELED
 * */
public enum OrderStatus {
    PROCESSING,
    AWAITING_PAYMENT,
    PAYING, // 此状态下,订单不可time-out和cancel
    PAID,
    FAILED,
    CANCELED,
    TIMEOUT
}

