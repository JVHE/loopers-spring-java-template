package com.loopers.application.order;

public record PaymentRequest(
        String cardType,
        String cardNo
) {
}
