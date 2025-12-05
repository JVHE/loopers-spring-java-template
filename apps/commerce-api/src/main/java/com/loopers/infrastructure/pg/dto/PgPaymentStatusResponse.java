package com.loopers.infrastructure.pg.dto;

public record PgPaymentStatusResponse(
        String paymentId,
        String status,
        String failureReason,
        String message
) {
}
