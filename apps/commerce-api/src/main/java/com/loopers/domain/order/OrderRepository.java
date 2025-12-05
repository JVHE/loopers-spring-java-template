package com.loopers.domain.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Optional<Order> findByIdAndUserId(Long id, Long userId);

    Optional<Order> findByPaymentId(String paymentId);

    List<Order> findPendingPaymentOrdersBefore(ZonedDateTime before);

    Order save(Order order);

    Page<Order> findByUserIdAndDeletedAtIsNull(Long userId, Pageable pageable);

    Optional<Order> findById(Long orderId);
}
