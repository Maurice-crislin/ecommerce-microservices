package org.example.orderservice.messaging;


import lombok.RequiredArgsConstructor;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.inventory.dto.StockRequest;
import org.common.order.enums.OutBoxEventType;
import org.common.payment.enums.PaymentStatus;
import org.example.orderservice.OrderRepository.OrderRepository;
import org.example.orderservice.OrderRepository.OutboxEventRepo;
import org.example.orderservice.entity.Order;
import org.example.orderservice.entity.OrderItem;
import org.common.order.enums.OrderStatus;
import org.example.orderservice.entity.OutboxEvent;
import org.example.orderservice.service.OrderBaseService;
import org.example.orderservice.service.OrderService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderMessageListener {
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final OutboxEventRepo outboxEventRepo;
    private final OrderBaseService orderBaseService;

    @RabbitListener(queues = RabbitMQConfig.ORDER_RELEASE_QUEUE)
    @Transactional
    @Retryable(
            maxAttempts = 5,
            backoff = @Backoff(delay = 2000,multiplier = 2.0),
            retryFor = {OptimisticLockingFailureException.class}
    )
    /** 支付流程（高频、涉及 HTTP、需确定性）→ 悲观锁
    超时流程（后台、低冲突、不应阻塞支付）→ 乐观锁 */
    public void handleOrderTimeOut(Long orderId){

        try{
            Order order = orderRepository.findOrderByOrderId(orderId).orElseThrow(()-> new IllegalArgumentException("orderId not found"));
            order.timeout();// update order status to “Timeout”
            // 如果发现当下状态不能timeout,直接抛错失败
            List<OrderItem> orderItems = order.getOrderItems();
            List<StockRequest>  stockRequests = orderItems
                    .stream()
                    .map((item)-> new StockRequest(item.getProductCode(), item.getQuantity()))
                    .toList();

            // 抢占式幂等：直接写入，利用 DB 唯一约束防重，冲突时忽略（避免补偿流程与正常流程并发回滚）
            try {
                OutboxEvent outboxEvent = new OutboxEvent();
                outboxEvent.setOrderId(orderId);
                String payload = orderBaseService.toJsonStr(new InventoryBatchRequest(orderId, stockRequests));
                outboxEvent.setPayload(payload);
                outboxEvent.setEventType(OutBoxEventType.UNLOCK);
                outboxEventRepo.save(outboxEvent);
            } catch (DataIntegrityViolationException e) {
                // db中已存在相同的 OutboxEvent，不用回滚
            }
        } catch(OptimisticLockingFailureException ex){
            throw ex;
        } catch (Exception ignored) {
            // order不存在 或者 该状态下不能timeout,不重试
        }
    }
}
