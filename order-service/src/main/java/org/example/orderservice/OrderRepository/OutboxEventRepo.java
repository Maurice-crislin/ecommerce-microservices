package org.example.orderservice.OrderRepository;

import org.common.order.enums.OutboxStatus;
import org.example.orderservice.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OutboxEventRepo extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findAllByStatus(OutboxStatus status);
}
