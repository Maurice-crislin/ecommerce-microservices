package org.example.orderservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.common.order.enums.OrderStatus;
import org.common.order.enums.OutBoxEventType;
import org.common.payment.enums.PaymentStatus;
import org.example.orderservice.OrderRepository.OrderRepository;
import org.example.orderservice.OrderRepository.OutboxEventRepo;
import org.example.orderservice.entity.Order;
import org.example.orderservice.entity.OrderItem;
import org.example.orderservice.entity.OutboxEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderBaseService {
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;
    private final OutboxEventRepo outboxEventRepo;

    @Transactional
    public Order markPaying(Long orderId) {
        // 悲观锁
        Order order = orderRepository.findOrderByOrderIdWithLock(orderId)
                .orElseThrow(() -> new IllegalArgumentException("order not found " + orderId));

        // 状态机幂等 只有 AWAITING_PAYMENT 才能进入支付流程
        order.lockPaying();
        return order;
    }

    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public void finalizeOrderAfterPayment(PaymentStatus paymentStatus, Long orderId) {
        // 悲观锁
        Order order = orderRepository.findOrderByOrderIdWithLock(orderId)
                .orElseThrow(() -> new IllegalArgumentException("order not found " + orderId));

        if (paymentStatus == PaymentStatus.PAID) {
            order.pay();
        } else {
            order.fail();
        }
        List<OrderItem> orderItems = order.getOrderItems();
        List<StockRequest> stockRequests = orderItems.stream()
                .map(item -> new StockRequest(item.getProductCode(), item.getQuantity()))
                .toList();

        String payload = toJsonStr(new InventoryBatchRequest(orderId, stockRequests));

        OutBoxEventType eventType = paymentStatus == PaymentStatus.PAID ? OutBoxEventType.CONFIRM : OutBoxEventType.UNLOCK;
        try {
            OutboxEvent outboxEvent = new OutboxEvent();
            outboxEvent.setOrderId(orderId);
            outboxEvent.setPayload(payload);
            outboxEvent.setEventType(eventType);
            outboxEventRepo.save(outboxEvent);
        } catch (DataIntegrityViolationException e) {
            // outbox 记录已存在, 补偿流程已处理, 忽略。
            // !!!!Outbox 唯一约束冲突不应导致订单状态回滚
            // noRollbackFor 确保 order.pay()/fail() 的状态变更不回滚。
        }
    }
    public String toJsonStr(InventoryBatchRequest inventoryBatchRequest) {
        try {
            return objectMapper.writeValueAsString(inventoryBatchRequest);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize object to JSON", e);
        }
    }
}