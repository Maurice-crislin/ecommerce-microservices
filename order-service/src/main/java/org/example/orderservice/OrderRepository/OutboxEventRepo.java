package org.example.orderservice.OrderRepository;

import org.common.order.enums.OutboxStatus;
import org.example.orderservice.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepo extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findAllByStatus(OutboxStatus status);

    @Query("""
        SELECT e FROM OutboxEvent e
        WHERE e.status = 'NEW'
        OR (e.status = 'SENDING' AND e.claimedAt < :timeout)
        ORDER BY e.createdAt ASC
    """)
    List<OutboxEvent> findAllNewAndExpiredSending(@Param("timeout") LocalDateTime timeout);

    @Modifying
    @Query("""
        UPDATE OutboxEvent e
        SET e.status = 'SENDING',
            e.claimedAt = CURRENT_TIMESTAMP
        WHERE e.id = :id
        AND (
            e.status = 'NEW'
            OR (
                e.status = 'SENDING'
                AND e.claimedAt < :timeout
            )
        )
    """) // 5分钟前 claim 的 SENDING 认为已经死亡,允许重新接管。
    int claimEvent(@Param("id")  Long id, @Param("timeout") LocalDateTime timeout);
}

