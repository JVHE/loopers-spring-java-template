package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.point.PointService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.supply.SupplyService;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class OrderFacade {
    private final UserService userService;
    private final OrderService orderService;
    private final ProductService productService;
    private final PointService pointService;
    private final SupplyService supplyService;

    @Transactional(readOnly = true)
    public OrderInfo getOrderInfo(String userId, Long orderId) {
        User user = userService.findByUserId(userId).orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        Order order = orderService.getOrderByIdAndUserId(orderId, user.getId());

        return OrderInfo.from(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderInfo> getOrderList(String userId, Pageable pageable) {
        User user = userService.findByUserId(userId).orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        Page<Order> orders = orderService.getOrdersByUserId(user.getId(), pageable);
        return orders.map(OrderInfo::from);
    }

    @Transactional
    public OrderInfo createOrder(String userId, OrderRequest request) {
        User user = userService.findByUserId(userId).orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        Map<Long, Integer> productIdQuantityMap = request.items().stream()
                .collect(Collectors.toMap(OrderItemRequest::productId, OrderItemRequest::quantity));

        Map<Long, Product> productMap = productService.getProductMapByIds(productIdQuantityMap.keySet());

        request.items().forEach(item -> {
            supplyService.checkAndDecreaseStock(item.productId(), item.quantity());
        });

        Integer totalAmount = productService.calculateTotalAmount(productIdQuantityMap);
        pointService.checkAndDeductPoint(user.getId(), totalAmount);

        Order order = orderService.createOrder(request.items(), productMap, user.getId());

        return OrderInfo.from(order);
    }
}
