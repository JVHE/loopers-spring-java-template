package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    Page<Order> findByUserIdAndDeletedAtIsNull(Long userId, Pageable pageable);

    Optional<Order> findByPgPaymentId(String pgPaymentId);

    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, ZonedDateTime createdAt);
}
