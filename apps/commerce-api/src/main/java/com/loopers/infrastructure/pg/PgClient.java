package com.loopers.infrastructure.pg;

import com.loopers.infrastructure.pg.dto.PgPaymentRequest;
import com.loopers.infrastructure.pg.dto.PgPaymentResponse;
import com.loopers.infrastructure.pg.dto.PgPaymentStatusResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "pgClient", url = "${pg.simulator.base-url}", configuration = PgClientConfig.class)
public interface PgClient {

    @PostMapping("/api/v1/payments")
    PgPaymentResponse requestPayment(
            @RequestHeader("X-USER-ID") String userId,
            @RequestBody PgPaymentRequest request
    );

    @GetMapping("/api/v1/payments/{paymentId}")
    PgPaymentStatusResponse getPaymentStatus(
            @RequestHeader("X-USER-ID") String userId,
            @PathVariable String paymentId
    );

    @GetMapping("/api/v1/payments")
    PgPaymentStatusResponse getPaymentByOrderId(
            @RequestHeader("X-USER-ID") String userId,
            @RequestParam("orderId") String orderId
    );
}
