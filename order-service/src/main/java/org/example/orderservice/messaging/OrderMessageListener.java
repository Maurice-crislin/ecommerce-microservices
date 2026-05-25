package org.example.orderservice.messaging;


import lombok.RequiredArgsConstructor;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.common.order.enums.OutBoxEventType;
import org.example.orderservice.OrderRepository.OrderRepository;
import org.example.orderservice.OrderRepository.OutboxEventRepo;
import org.example.orderservice.entity.Order;
import org.example.orderservice.entity.OrderItem;
import org.example.orderservice.entity.OutboxEvent;
import org.example.orderservice.service.OrderBaseService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderMessageListener {
    private final OrderRepository orderRepository;
    private final OutboxEventRepo outboxEventRepo;
    private final OrderBaseService orderBaseService;

    @RabbitListener(queues = RabbitMQConfig.ORDER_RELEASE_QUEUE)
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    @Retryable(
            maxAttempts = 5,
            backoff = @Backoff(delay = 2000, multiplier = 2.0),
            retryFor = {OptimisticLockingFailureException.class}
    )
    public void handleOrderTimeOut(Long orderId) {

        try {
            Order order = orderRepository.findOrderByOrderId(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("orderId not found"));

            // 乐观锁: @Version 控制并发, @Retryable 自动重试
            order.timeout();

            List<OrderItem> orderItems = order.getOrderItems();
            List<StockRequest> stockRequests = orderItems.stream()
                    .map(item -> new StockRequest(item.getProductCode(), item.getQuantity()))
                    .toList();

            // 抢占式幂等: 直接写入, DB 唯一约束防重, 冲突时忽略
            // noRollbackFor = DataIntegrityViolationException.class 确保事务不回滚
            try {
                OutboxEvent outboxEvent = new OutboxEvent();
                outboxEvent.setOrderId(orderId);
                outboxEvent.setPayload(orderBaseService.toJsonStr(new InventoryBatchRequest(orderId, stockRequests)));
                outboxEvent.setEventType(OutBoxEventType.UNLOCK);
                outboxEventRepo.save(outboxEvent);
            } catch (DataIntegrityViolationException e) {
                // outbox 记录已存在, 补偿流程已处理, 忽略
                // !!!!Outbox 唯一约束冲突不应导致订单状态回滚
            }

        } catch (OptimisticLockingFailureException ex) {
            throw ex;
        } catch (Exception ignored) {
            // order 不存在 -IllegalArgumentException
            // 或 该状态下不能 timeout (-IllegalStateException), 如已支付
            // 不重试
        }
    }
}