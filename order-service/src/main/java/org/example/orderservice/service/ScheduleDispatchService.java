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

import java.util.List;


@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleDispatchService {
    private final OutboxEventRepo outboxEventRepo;
    private final OutboxProcessorService outboxProcessorService;
    @Scheduled(fixedDelay = 5000)
    public void dispatchInventoryEvent() {
        List<OutboxEvent> events= outboxEventRepo.findAllByStatus(OutboxStatus.NEW);

        if (events.isEmpty()) {
            return;
        }

        for(OutboxEvent event : events){
            outboxProcessorService.processOutboxEvent(event.getId());
        }
    }
}
