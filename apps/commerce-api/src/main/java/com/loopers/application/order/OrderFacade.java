package com.loopers.application.order;

import com.loopers.domain.common.vo.DiscountResult;
import com.loopers.domain.common.vo.Price;
import com.loopers.domain.coupon.CouponService;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentFailureReason;
import com.loopers.domain.point.PointService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.supply.SupplyService;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserService;
import com.loopers.infrastructure.payment.PaymentCallbackUrlGenerator;
import com.loopers.infrastructure.pg.PgClient;
import com.loopers.infrastructure.pg.dto.PgPaymentRequest;
import com.loopers.infrastructure.pg.dto.PgPaymentResponse;
import com.loopers.infrastructure.pg.dto.PgPaymentStatusResponse;
import com.loopers.interfaces.api.payment.PaymentCallbackRequest;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class OrderFacade {
    private final CouponService couponService;
    private final OrderService orderService;
    private final ProductService productService;
    private final PointService pointService;
    private final SupplyService supplyService;
    private final UserService userService;

    private final PgClient pgClient;
    private final PaymentCallbackUrlGenerator callbackUrlGenerator;

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
        request.validate();

        Map<Long, Integer> productIdQuantityMap = request.toItemQuantityMap();

        productIdQuantityMap.forEach(supplyService::checkAndDecreaseStock);

        List<Product> products = productService.getProductsByIds(productIdQuantityMap.keySet());
        Map<Product, Integer> productQuantityMap = products.stream().collect(Collectors.toMap(
                Function.identity(),
                product -> productIdQuantityMap.get(product.getId())
        ));

        Price originalPrice = productQuantityMap.entrySet().stream()
                .map(entry -> entry.getKey().getPrice().multiply(entry.getValue()))
                .reduce(new Price(0), Price::add);

        DiscountResult discountResult = getDiscountResult(request.couponId(), user, originalPrice);

        pointService.checkAndDeductPoint(user.getId(), discountResult.finalPrice());
        Order order = orderService.createOrder(productQuantityMap, user.getId(), discountResult);
        Long orderId = order.getId();

        // 결제 정보 설정
        if (request.paymentRequest() != null) {
            String callbackUrl = callbackUrlGenerator.generateCallbackUrl(orderId);

            order.setPaymentInfo(
                    request.paymentRequest().cardType(),
                    request.paymentRequest().cardNo(),
                    callbackUrl
            );
//            order = orderService.save(order);  // 결제 정보 저장
            // 비동기로 PG 결제 요청
            PgPaymentRequest pgRequest = new PgPaymentRequest(
                    String.valueOf(orderId),
                    request.paymentRequest().cardType(),
                    request.paymentRequest().cardNo(),
                    String.valueOf(discountResult.finalPrice().amount()),
                    callbackUrl  // 서버에서 생성한 콜백 URL
            );

            requestPaymentAsync(userId, pgRequest)
                    .thenAccept(pgResponse -> {
                        // 결제 요청 성공 시 paymentId 업데이트
                        if (pgResponse.paymentId() != null) {
                            orderService.updateOrderStatus(
                                    orderId,
                                    OrderStatus.PENDING,
                                    pgResponse.paymentId()
                            );
                        }
                    })
                    .exceptionally(throwable -> {
                        System.out.println("결제 요청 실패 - orderId: " + orderId + ", " + throwable.getMessage());
                        return null;
                    });
        }
        return OrderInfo.from(order);
    }

    private DiscountResult getDiscountResult(Long couponId, User user, Price originalPrice) {
        if (couponId != null) {
            return couponService.applyCoupon(couponId, user.getId(), originalPrice);
        }
        return new DiscountResult(originalPrice);
    }


    // 결제 요청
    @CircuitBreaker(name = "pgCircuit", fallbackMethod = "requestPaymentFallback")
    @Retry(name = "pgRetry", fallbackMethod = "requestPaymentFallback")
    @TimeLimiter(name = "pgTimeout")
    public CompletableFuture<PgPaymentResponse> requestPaymentAsync(String userId, PgPaymentRequest request) {
        return CompletableFuture.supplyAsync(() -> pgClient.requestPayment(userId, request));
    }

    // 결제 요청 실패
    public CompletableFuture<PgPaymentResponse> requestPaymentFallback(String userId, PgPaymentRequest request, Throwable throwable) {
        System.out.println("Payment request failed: " + throwable.getMessage());

        // fallback 응답 반환. 결제 대기 상태로 저장
        PgPaymentResponse fallbackResponse = new PgPaymentResponse(
                null,
                "PENDING",
                "결제 요청이 지연되고 있습니다. 잠시 후 상태를 확인해주세요."
        );
        return CompletableFuture.completedFuture(fallbackResponse);
    }

    // 결제 상태 확인
    @CircuitBreaker(name = "pgCircuit", fallbackMethod = "getPaymentStatusFallback")
    @Retry(name = "pgRetry", fallbackMethod = "getPaymentStatusFallback")
    @TimeLimiter(name = "pgTimeout")
    private CompletableFuture<PgPaymentStatusResponse> getPaymentStatusAsync(String userId, String paymentId) {
        return CompletableFuture.supplyAsync(() -> pgClient.getPaymentStatus(userId, paymentId));
    }

    // 결제 상태 확인 실패
    public CompletableFuture<PgPaymentStatusResponse> getPaymentStatusFallback(String userId, String paymentId, Throwable throwable) {
        System.out.println("Payment status check failed: " + throwable.getMessage());

        // fallback 응답 반환. 결제 상태를 알 수 없음
        PgPaymentStatusResponse fallbackStatus = new PgPaymentStatusResponse(
                paymentId,
                "UNKNOWN",
                "TIMEOUT",
                "결제 상태 확인이 지연되고 있습니다. 잠시 후 다시 시도해주세요."
        );
        return CompletableFuture.completedFuture(fallbackStatus);
    }

    // 주문 ID로 결제 상태 확인
    @CircuitBreaker(name = "pgCircuit", fallbackMethod = "getPaymentByOrderIdFallback")
    @TimeLimiter(name = "pgTimeout")
    public CompletableFuture<PgPaymentStatusResponse> getPaymentByOrderIdAsync(String userId, String orderId) {
        return CompletableFuture.supplyAsync(() -> pgClient.getPaymentByOrderId(userId, orderId));
    }

    // 주문 ID로 결제 상태 확인 실패
    public CompletableFuture<PgPaymentStatusResponse> getPaymentByOrderIdFallback(String userId, String orderId, Throwable throwable) {
        System.out.println("Payment status by order ID check failed: " + throwable.getMessage());

        // fallback 응답 반환. 결제 상태를 알 수 없음
        PgPaymentStatusResponse fallbackStatus = new PgPaymentStatusResponse(
                null,
                "UNKNOWN",
                "TIMEOUT",
                "결제 상태 확인이 지연되고 있습니다. 잠시 후 다시 시도해주세요."
        );
        return CompletableFuture.completedFuture(fallbackStatus);
    }

    @Transactional
    public void handleCallback(Long orderId, PaymentCallbackRequest request) {
        // PG에 상태 재확인
        PgPaymentStatusResponse statusResponse = getPaymentByOrderIdAsync("SYSTEM", String.valueOf(orderId))
                .exceptionally(throwable -> {
                    System.out.println("PG 상태 재확인 실패 - orderId: " + orderId + ", " + throwable.getMessage());
                    return null;
                })
                .join();

        if (statusResponse != null) {
            System.out.println("PG 상태 재확인 성공 - orderId: " + orderId + ", status: " + statusResponse.status());
            // PG에서 받은 상태로 덮어쓰기
            request = new PaymentCallbackRequest(
                    statusResponse.paymentId(),
                    statusResponse.status(),
                    statusResponse.failureReason()
            );
        } else {
            System.out.println("PG 상태 재확인 불가 - orderId: " + orderId + ", 기존 콜백 상태 사용");
        }

        // 결제 상태 업데이트
        OrderStatus status = mapPgStatusToOrderStatus(request.status());
        orderService.updateOrderStatus(orderId, status, request.paymentId());

        // 결제 실패 시 실패 사유 저장
        if (status == OrderStatus.FAILED) {
            PaymentFailureReason reason = mapFailureReason(request.failureReason());
            orderService.markPaymentFailed(orderId, reason);
        }
    }

    private OrderStatus mapPgStatusToOrderStatus(String pgStatus) {
        return switch (pgStatus.toUpperCase()) {
            case "SUCCESS" -> OrderStatus.PAID;
            case "FAILED" -> OrderStatus.FAILED;
            default -> OrderStatus.PENDING;
        };
    }

    private PaymentFailureReason mapFailureReason(String reason) {
        if (reason == null) {
            return PaymentFailureReason.UNKNOWN;
        }
        return switch (reason.toUpperCase()) {
            case "LIMITED_FUNDS" -> PaymentFailureReason.LIMITED_FUNDS;
            case "INVALID_CARD" -> PaymentFailureReason.INVALID_CARD;
            default -> PaymentFailureReason.UNKNOWN;
        };
    }

    public OrderInfoDetail getOrderInfoByPaymentId(String userId, String paymentId) {
        Order order = orderService.findByPaymentId(paymentId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
        return OrderInfoDetail.from(order);
    }
}
