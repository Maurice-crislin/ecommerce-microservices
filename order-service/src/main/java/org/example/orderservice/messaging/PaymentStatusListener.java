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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PaymentStatusListener {

    private final OrderRepository orderRepository;
    private final  InventoryEventProducer  inventoryEventProducer;
    private final PaymentClient paymentClient;

    @RabbitListener(queues = RabbitMQConfig.PAYMENT_SUCCESS_STATUS_QUEUE)
    @Transactional
    public void handlePaymentSuccess(PaymentStatusMessage message) {

        Long orderId = message.getOrderId();
        Order order = orderRepository.findById(orderId).orElseThrow(()->new IllegalArgumentException("Order id not found"));

        // race condition check
        if (order.getOrderStatus().equals(OrderStatus.TIMEOUT)) {
            // refund
            RefundResponse refundResponse = paymentClient.refund(message.getPaymentNo());
            return;
        }
        order.pay();

        // batch notice confirm sale
        List<OrderItem> orderItems = order.getOrderItems();
        List<StockRequest>  stockRequests = orderItems
                .stream()
                .map((item)-> new StockRequest(item.getProductCode(), item.getQuantity()))
                .toList();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        inventoryEventProducer.sendBatchConfirmStockEvent(new InventoryBatchRequest(orderId,stockRequests));
                    }
                }
        );
    }

    @RabbitListener(queues = RabbitMQConfig.PAYMENT_FAILED_STATUS_QUEUE)
    @Transactional
    public void handlePaymentFailed(PaymentStatusMessage message) {
        Long orderId = message.getOrderId();
        Order order = orderRepository.findById(orderId).orElseThrow(()->new IllegalArgumentException("Order id not found"));

        order.fail();

        // batch notice unlock inventory
        List<OrderItem> orderItems = order.getOrderItems();
        List<StockRequest>  stockRequests = orderItems
                .stream()
                .map((item)-> new StockRequest(item.getProductCode(), item.getQuantity()))
                .toList();

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        inventoryEventProducer.sendBatchUnlockStockEvent(new InventoryBatchRequest(orderId,stockRequests));
                    }
                }
        );
    }

}
