package com.loopers.interfaces.api.payment;

public record PaymentCallbackRequest(
        String paymentId,
        String status,
        String failureReason
) {}
