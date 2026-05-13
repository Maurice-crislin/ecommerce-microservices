package org.example.inventoryservice.config;



public class RedisKeys {
    private static final String PREFIX = "inventory:stock:";


    public static String availableStockKey(Long productCode) {
        return PREFIX + productCode + ":available";
    }

    public static String lockedStockKey(Long productCode) {
        return PREFIX + productCode + ":locked";
    }
}
