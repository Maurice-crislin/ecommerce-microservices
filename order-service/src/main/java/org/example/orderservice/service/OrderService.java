package org.example.orderservice.service;

import org.common.inventory.dto.InventoryBatchRequest;
import org.common.payment.enums.PaymentStatus;
import org.example.orderservice.dto.OrderRequest;
import org.example.orderservice.entity.Order;

//    Order → Product（批量查价）	REST API	强一致、立即需要结果
//    Order → Inventory（批量预占）	REST API	是否能下单是同步决策
//    Order → Product	REST API	同步创建支付
//    Order <- Payment（结果返回）	REST API
//    Order → Inventory（确认/释放库存）	MQ	Saga 补偿、最终一致
public interface OrderService  {
    void createOrder(OrderRequest request);
    void payOrder(Long orderId);
}
