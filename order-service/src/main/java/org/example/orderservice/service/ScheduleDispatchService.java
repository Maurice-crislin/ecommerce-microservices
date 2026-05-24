package org.example.orderservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.common.inventory.dto.InventoryBatchRequest;
import org.common.order.enums.OutBoxEventType;
import org.common.order.enums.OutboxStatus;
import org.example.orderservice.OrderRepository.OutboxEventRepo;
import org.example.orderservice.entity.OutboxEvent;
import org.example.orderservice.messaging.InventoryEventProducer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleDispatchService {
    private final OutboxEventRepo outboxEventRepo;
    private final InventoryEventProducer inventoryEventProducer;
    private final ObjectMapper objectMapper;
    @Scheduled(fixedDelay = 5000)
    public void dispatchInventoryEvent() {
        List<OutboxEvent> events= outboxEventRepo.findAllByStatus(OutboxStatus.NEW);

        if (events.isEmpty()) {
            return;
        }

        for(OutboxEvent event : events){
            OutBoxEventType eventType = event.getEventType();
            String payload = event.getPayload();

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
                event.markAsSent();
                outboxEventRepo.save(event);
            } catch (Exception e){
                // 发送失败或反序列化失败，标记为失败并记录日志
                log.error("Failed to dispatch outbox event id: {}, type: {}", event.getId(), eventType, e);
                event.recordDispatchFailure();
                outboxEventRepo.save(event);
            }
        }

    }
}
