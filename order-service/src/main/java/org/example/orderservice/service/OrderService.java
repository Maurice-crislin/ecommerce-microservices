package org.example.orderservice.service;

import org.example.orderservice.dto.OrderRequest;

public interface OrderService  {
//    Order → Product（批量查价）	REST API	强一致、立即需要结果
//    Order → Inventory（批量预占）	REST API	是否能下单是同步决策
//    Order → Product	REST API	同步创建支付
//    Order <- Payment（结果通知）	MQ	天然异步、状态驱动
//    Order → Inventory（确认/释放库存）	MQ	Saga 补偿、最终一致
    void createOrder(OrderRequest request);
    void payOrder(Long orderId);
}
