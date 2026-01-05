package org.example.orderservice.OrderRepository;

import org.example.orderservice.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findOrderByOrderId(Long orderId);
}
