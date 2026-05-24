package org.example.orderservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
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

import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
public class OrderBaseService {
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;
    private final OutboxEventRepo outboxEventRepo;
    @Transactional
    public Order markPaying(Long orderId) {
        // 悲观锁
        Order order =  orderRepository.findOrderByOrderIdWithLock(orderId)
                .orElseThrow(() -> new IllegalArgumentException("order not found " + orderId));

        // 状态机幂等 只有 AWAITING_PAYMENT 才能进入支付流程
        if (order.getOrderStatus() != OrderStatus.AWAITING_PAYMENT) {
            throw new IllegalStateException("order can not pay now");
        }
        order.lockPaying();
        return order;
    }


    @Transactional
    public void finalizeOrderAfterPayment(PaymentStatus paymentStatus, Long orderId) {
        // 悲观锁
        Order order =  orderRepository.findOrderByOrderIdWithLock(orderId)
                .orElseThrow(() -> new IllegalArgumentException("order not found " + orderId));

        if (paymentStatus == PaymentStatus.PAID) {
            // 支付成功 → 更新订单状态 + 确认库存
            order.pay();
        } else {
            // 支付失败 → 更新订单状态 + 释放库存
            order.fail();
        }
        List<OrderItem> orderItems = order.getOrderItems();
        List<StockRequest> stockRequests = orderItems.stream()
                .map(item -> new StockRequest(item.getProductCode(), item.getQuantity()))
                .toList();

        String payload = toJsonStr(new InventoryBatchRequest(orderId, stockRequests));

        // 抢占式幂等：直接写入，利用 DB 唯一约束防重，冲突时忽略（避免补偿流程与正常流程并发回滚）
        OutBoxEventType eventType = paymentStatus == PaymentStatus.PAID ? OutBoxEventType.CONFIRM : OutBoxEventType.UNLOCK;
        try {
            OutboxEvent outboxEvent = new OutboxEvent();
            outboxEvent.setOrderId(orderId);
            outboxEvent.setPayload(payload);
            outboxEvent.setEventType(eventType);
            outboxEventRepo.save(outboxEvent);
        } catch (DataIntegrityViolationException e) {
            // db中已存在相同的 OutboxEvent，是由补偿流程插入的,不用回滚order状态
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
