package org.example.orderservice.OrderRepository;

import org.common.order.enums.OrderStatus;
import org.example.orderservice.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findOrderByOrderId(Long orderId);

    Boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<Order> findByIdForUpdate(Long orderId);

    @Query("SELECT o FROM Order o WHERE o.orderStatus = :status AND o.updatedAt < :before")
    List<Order> findByStatusAndUpdatedAtBefore(@Param("status") OrderStatus status, @Param("before") LocalDateTime before);
}
