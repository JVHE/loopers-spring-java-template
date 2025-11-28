package com.loopers.domain.order;

import com.loopers.application.order.OrderItemRequest;
import com.loopers.domain.product.Product;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class OrderService {
    private final OrderRepository orderRepository;

    public Order save(Order order) {
        return orderRepository.save(order);
    }

    public Order createOrder(List<OrderItemRequest> OrderItems, Map<Long, Product> productMap, Long userId) {
        List<OrderItem> orderItems = OrderItems
                .stream()
                .map(item -> OrderItem.create(
                        item.productId(),
                        productMap.get(item.productId()).getName(),
                        item.quantity(),
                        productMap.get(item.productId()).getPrice()
                ))
                .toList();
        Order order = Order.create(userId, orderItems);

        return orderRepository.save(order);
    }

    public Order getOrderByIdAndUserId(Long orderId, Long userId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
    }

    public Page<Order> getOrdersByUserId(Long userId, Pageable pageable) {
        int page = pageable.getPageNumber();
        int size = pageable.getPageSize();
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        return orderRepository.findByUserIdAndDeletedAtIsNull(userId, PageRequest.of(page, size, sort));
    }
}
