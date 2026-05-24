package org.common.order.enums;

/**
 NEW
    -> SENDING
    -> SENT

        SENDING
            -> NEW

        SENDING
            -> FAILED_FINAL
 */
public enum OutboxStatus {
    NEW,
    SENDING,// 抢占态
    SENT,
    FAILED_FINAL,
}

