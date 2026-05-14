package org.common.mq.constants;

/**
 * 只有exchange和routeKey需要共享,queue是消费方私有信息
 * PRODUCT VS SEARCH
 */
public class ProductMQConstants {
    private ProductMQConstants() {}

    public static final String EXCHANGE_NAME = "PRODUCT_EXCHANGE";
    public static final String UPDATED_ROUTE_KEY = "UPDATED_ROUTE_KEY";
    public static final String DELETED_ROUTE_KEY = "DELETED_ROUTE_KEY";
    public static final String CREATED_ROUTE_KEY = "CREATED_ROUTE_KEY";

}