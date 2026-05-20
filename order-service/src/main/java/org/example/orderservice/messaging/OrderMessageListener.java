package org.example.orderservice.messaging;


import lombok.RequiredArgsConstructor;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.example.orderservice.OrderRepository.OrderRepository;
import org.example.orderservice.entity.Order;
import org.example.orderservice.entity.OrderItem;
import org.common.order.enums.OrderStatus;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderMessageListener {
    private final OrderRepository orderRepository;
    private final InventoryEventProducer inventoryEventProducer;
    @RabbitListener(queues = RabbitMQConfig.ORDER_RELEASE_QUEUE)
    public void handleOrderTimeOut(Long orderId){
        Order order = orderRepository.findOrderByOrderId(orderId).orElseThrow(()-> new IllegalArgumentException("orderId not found"));
        if (order.getOrderStatus() == OrderStatus.PROCESSING){


            List<OrderItem> orderItems = order.getOrderItems();
            List<StockRequest>  stockRequests = orderItems
                    .stream()
                    .map((item)-> new StockRequest(item.getProductCode(), item.getQuantity()))
                    .toList();

            inventoryEventProducer.sendBatchUnlockStockEvent(new InventoryBatchRequest(orderId,stockRequests));
            // update order status to “Timeout”
            order.timeout();
            orderRepository.save(order);
        }
    }
}
