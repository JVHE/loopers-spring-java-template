package com.loopers.domain.order;

public enum PaymentFailureReason {
    LIMITED_FUNDS,
    INVALID_CARD,
    TIMEOUT,
    PG_ERROR,
    UNKNOWN
}
