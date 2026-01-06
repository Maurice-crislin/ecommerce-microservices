package org.example.orderservice.messaging;

import lombok.RequiredArgsConstructor;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.common.order.enums.OrderStatus;
import org.common.payment.dto.RefundResponse;
import org.common.payment.message.PaymentStatusMessage;
import org.example.orderservice.OrderRepository.OrderRepository;
import org.example.orderservice.client.PaymentClient;
import org.example.orderservice.entity.Order;
import org.example.orderservice.entity.OrderItem;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PaymentStatusListener {

    private final OrderRepository orderRepository;
    private final  InventoryEventProducer  inventoryEventProducer;
    private final PaymentClient paymentClient;
    @RabbitListener(queues = RabbitMQConfig.PAYMENT_SUCCESS_STATUS_QUEUE)
    public void handlePaymentSuccess(PaymentStatusMessage message) {

        Long orderId = message.getOrderId();
        Order order = orderRepository.findById(orderId).orElseThrow(()->new IllegalArgumentException("Order id not found"));

        // race condition check
        if (order.getOrderStatus().equals(OrderStatus.TIMEOUT)) {
            // refund
            RefundResponse refundResponse = paymentClient.refund(message.getPaymentNo());
            return;
        }
        order.setOrderStatus(OrderStatus.PAID);
        orderRepository.save(order);

        // batch notice confirm sale
        List<OrderItem> orderItems = order.getOrderItems();
        List<StockRequest>  stockRequests = orderItems
                .stream()
                .map((item)-> new StockRequest(item.getProductCode(), item.getQuantity()))
                .toList();
        inventoryEventProducer.sendBatchConfirmStockEvent(new InventoryBatchRequest(orderId,stockRequests));

    }

    @RabbitListener(queues = RabbitMQConfig.PAYMENT_FAILED_STATUS_QUEUE)
    public void handlePaymentFailed(PaymentStatusMessage message) {
        Long orderId = message.getOrderId();
        Order order = orderRepository.findById(orderId).orElseThrow(()->new IllegalArgumentException("Order id not found"));

        order.setOrderStatus(OrderStatus.FAILED);
        orderRepository.save(order);

        // batch notice unlock inventory
        List<OrderItem> orderItems = order.getOrderItems();
        List<StockRequest>  stockRequests = orderItems
                .stream()
                .map((item)-> new StockRequest(item.getProductCode(), item.getQuantity()))
                .toList();

        inventoryEventProducer.sendBatchUnlockStockEvent(new InventoryBatchRequest(orderId,stockRequests));
    }

}
