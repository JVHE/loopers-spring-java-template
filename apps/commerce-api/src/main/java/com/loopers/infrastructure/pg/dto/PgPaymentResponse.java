package com.loopers.infrastructure.pg.dto;

public record PgPaymentResponse(
        String paymentId,
        String status,
        String message
) {
}
