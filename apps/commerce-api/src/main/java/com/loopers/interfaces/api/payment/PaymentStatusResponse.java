package com.loopers.interfaces.api.payment;


import com.loopers.application.order.OrderInfoDetail;
import com.loopers.domain.order.OrderStatus;

public record PaymentStatusResponse(
        Long orderId,
        String pgPaymentId,
        OrderStatus status,
        String failureReason,
        Integer amount
) {
    public static PaymentStatusResponse from(OrderInfoDetail order) {
        return new PaymentStatusResponse(
                order.orderId(),
                order.paymentId(),
                order.status(),
                order.failureReason().name(),
                order.totalPrice()
        );
    }
}
