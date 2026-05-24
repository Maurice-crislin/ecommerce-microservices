package org.example.orderservice.OrderRepository;

import org.common.order.enums.OutboxStatus;
import org.example.orderservice.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventRepo extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findAllByStatus(OutboxStatus status);

    @Modifying
    @Query("""
        UPDATE OutboxEvent e
        SET e.status = 'SENDING'
        WHERE e.id = :id
        AND e.status = 'NEW'
    """)
    int claimEvent(@Param("id")  Long id);
}

