package org.example.orderservice.OrderRepository;

import org.common.order.enums.OutboxStatus;
import org.example.orderservice.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepo extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findAllByStatus(OutboxStatus status);
}
