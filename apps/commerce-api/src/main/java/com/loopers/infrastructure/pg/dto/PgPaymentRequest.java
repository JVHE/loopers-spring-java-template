package com.loopers.infrastructure.pg.dto;

public record PgPaymentRequest(
        String orderId,
        String cardType,
        String cardNo,
        String amount,
        String callbackUrl
) {
}
