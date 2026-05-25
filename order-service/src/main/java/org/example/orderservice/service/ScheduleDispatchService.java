package org.example.orderservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.orderservice.OrderRepository.OutboxEventRepo;
import org.example.orderservice.entity.OutboxEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;


@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleDispatchService {
    private final OutboxEventRepo outboxEventRepo;
    private final OutboxProcessorService outboxProcessorService;

    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);

    /**
     * 每5秒轮询一次 outbox 表, 处理 NEW 事件和已超时(>=5分钟前 claim)的 SENDING 事件。
     * SENDING 事件的超时恢复可防止处理线程崩溃导致消息永久丢失。
     */
    @Scheduled(fixedDelay = 5000)
    public void dispatchInventoryEvent() {
        LocalDateTime timeout = LocalDateTime.now().minus(CLAIM_TIMEOUT);
        List<OutboxEvent> events = outboxEventRepo.findAllNewAndExpiredSending(timeout);

        if (events.isEmpty()) {
            return;
        }

        for (OutboxEvent event : events) {
            outboxProcessorService.processOutboxEvent(event.getId());
        }
    }
}
