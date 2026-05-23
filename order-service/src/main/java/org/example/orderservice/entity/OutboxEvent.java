package org.example.orderservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.common.order.enums.OutBoxEventType;
import org.common.order.enums.OutboxStatus;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name="outbox_event",uniqueConstraints = @UniqueConstraint(columnNames = {"order_id", "event_type"}))
public class OutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private OutBoxEventType eventType; // mq消息是什么事件

    @Column(nullable = false)
    private Long orderId;   // 聚合根ID，用于日志和监控

    @Lob
    @Column(nullable = false)
    private String payload; // JSON 字符串，存储 InventoryBatchRequest

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status = OutboxStatus.NEW;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime sentAt;


    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();

        if (this.status == null) {
            this.status = OutboxStatus.NEW;
        }
    }
    public void markAsSent() {
        if (this.status == OutboxStatus.NEW) {
            this.sentAt = LocalDateTime.now();
            this.status = OutboxStatus.SENT;
        } else {
            throw new IllegalStateException("Cannot mark as sent");
        }
    }

    public void markAsFailed() {
        if (this.status == OutboxStatus.NEW) {
            this.status = OutboxStatus.FAILED;
        }  else {
            throw new IllegalStateException("Cannot mark as failed");
        }
    }

}

