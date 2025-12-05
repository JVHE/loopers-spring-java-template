package com.loopers.interfaces.api.payment;

import com.loopers.application.order.OrderFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments/callback")
public class PaymentCallbackV1Controller {
    private final OrderFacade orderFacade;

    @PostMapping
    public ApiResponse<Void> handleCallback(
            @RequestHeader(value = "X-USER-ID", required = false) String userId,
            @RequestParam("orderId") Long orderId,  // URL 파라미터로 orderId 받음
            @RequestBody PaymentCallbackRequest request) {
        System.out.println("PG 콜백 수신 - orderId: " + orderId + ", paymentId: " + request.paymentId() + ", status: " + request.status());

        orderFacade.handleCallback(orderId, request);

        return ApiResponse.success(null);
    }
}
