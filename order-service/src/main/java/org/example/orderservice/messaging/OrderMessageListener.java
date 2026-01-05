package org.example.orderservice.messaging;


import lombok.RequiredArgsConstructor;
import org.example.orderservice.OrderRepository.OrderRepository;
import org.example.orderservice.entity.Order;
import org.example.orderservice.entity.OrderItem;
import org.common.order.enums.OrderStatus;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderMessageListener {
    private final OrderRepository orderRepository;
    private final InventoryEventProducer inventoryEventProducer;
    @RabbitListener(queues = RabbitMQConfig.ORDER_RELEASE_QUEUE)
    public void handleOrderTimeOut(Long orderId){
        Order order = orderRepository.findOrderByOrderId(orderId).orElseThrow(()-> new IllegalArgumentException("orderId not found"));
        if (order.getOrderStatus() == OrderStatus.PROCESSING){

            // send unlock event for each order item
            for(OrderItem orderItem : order.getOrderItems()){
                inventoryEventProducer.sendUnlockStockEvent(
                        orderItem.getProductCode(),
                        orderItem.getQuantity(),
                        order.getOrderId()
                );
            }
            // update order status to “Timeout”
            order.setOrderStatus(OrderStatus.TIMEOUT);
            orderRepository.save(order);
        }
    }
}
