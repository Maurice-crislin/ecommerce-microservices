package org.example.orderservice.utils;

import cn.hutool.core.util.IdUtil;
import org.springframework.stereotype.Component;

/**
 * 基于 Hutool Snowflake 的 ID 生成器。
 * 生成时间有序递增的 Long 型 ID, 聚簇索引友好, 始终为正数。
 */
@Component
public class IdGenerator {

    private static final long DEFAULT_WORKER_ID = 1;
    private static final long DEFAULT_DATACENTER_ID = 1;

    /**
     * 生成下一个 Snowflake ID
     */
    public long nextId() {
        return IdUtil.getSnowflake(DEFAULT_WORKER_ID, DEFAULT_DATACENTER_ID).nextId();
    }

    /**
     * 生成订单 ID (对外调用)
     */
    public Long generateOrderId() {
        return nextId();
    }
}