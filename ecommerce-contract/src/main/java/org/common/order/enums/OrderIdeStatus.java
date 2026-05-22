package org.common.order.enums;

public enum OrderIdeStatus {
    PROCESSING, // 5min
    SUCCESS, // 24h
    FAILED_RETRY, // 30min
    FAILED_FINAL // 24h
}
