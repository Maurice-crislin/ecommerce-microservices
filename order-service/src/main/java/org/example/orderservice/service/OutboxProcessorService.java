package org.example.orderservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.order.enums.OutBoxEventType;
import org.example.orderservice.OrderRepository.OutboxEventRepo;
import org.example.orderservice.entity.OutboxEvent;
import org.example.orderservice.messaging.InventoryEventProducer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxProcessorService {

    private final OutboxEventRepo outboxEventRepo;
    private final InventoryEventProducer inventoryEventProducer;
    private final ObjectMapper objectMapper;

    @Transactional
    public void processOutboxEvent(Long id){
        int claim = outboxEventRepo.claimEvent(id);
        if (claim == 0) {
            // 未抢占到
            return;
        }
        // update之后,需要重新search
        OutboxEvent claimedEvent =
                outboxEventRepo.findById(id)
                        .orElseThrow();
        OutBoxEventType eventType = claimedEvent.getEventType();
        String payload = claimedEvent.getPayload();

        try{
            // 1. 反序列化 JSON 为 InventoryBatchRequest
            InventoryBatchRequest request = objectMapper.readValue(payload, InventoryBatchRequest.class);
            // 2. 根据事件类型发送消息
            if(eventType == OutBoxEventType.CONFIRM){
                inventoryEventProducer.sendBatchConfirmStockEvent(request);
            } else if(eventType == OutBoxEventType.UNLOCK) {
                inventoryEventProducer.sendBatchUnlockStockEvent(request);
            }
            // 3. 发送成功，标记为已发送
            claimedEvent.markAsSent();
            outboxEventRepo.save(claimedEvent);
        } catch (Exception e){
            // 发送失败或反序列化失败，标记为失败并记录日志
            log.error("Failed to dispatch outbox event id: {}, type: {}, retrycount: {}", claimedEvent.getId(), eventType, claimedEvent.getRetryCount(),e);
            claimedEvent.recordDispatchFailure();
            outboxEventRepo.save(claimedEvent);
        }
    }
}
