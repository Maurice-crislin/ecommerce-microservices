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
    private Integer retryCount = 0;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime sentAt;

    // claim out time recovery
    private LocalDateTime claimedAt;

    public OutboxEvent(){
        this.createdAt = LocalDateTime.now();
    }

    public void markAsSent() {
        if (this.status == OutboxStatus.SENDING) {
            this.sentAt = LocalDateTime.now();
            this.claimedAt = null;
            this.status = OutboxStatus.SENT;
        } else {
            throw new IllegalStateException("Cannot mark as sent");
        }
    }

    public void recordDispatchFailure(){

        if (this.status == OutboxStatus.SENT) {
            throw new IllegalStateException("Cannot mark as failed");
        }
        if (this.status == OutboxStatus.FAILED_FINAL){
            throw new IllegalStateException("Failed yet");
        }
        if (this.status == OutboxStatus.NEW) {
            throw new IllegalStateException("Event should be claimed");
        }

        // sending
        this.retryCount++;
        if(this.retryCount > 5) {
            this.status = OutboxStatus.FAILED_FINAL;
        } else {
            // sending->new, reset for next time claim
            this.status = OutboxStatus.NEW;
        }

    }

}

