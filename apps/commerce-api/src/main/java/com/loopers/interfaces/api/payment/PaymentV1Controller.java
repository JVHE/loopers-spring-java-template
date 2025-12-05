package com.loopers.interfaces.api.payment;

import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfoDetail;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentV1Controller {
    private final OrderFacade orderFacade;  // PaymentFacade 대신 OrderFacade 사용

    @GetMapping("/{paymentId}")
    public ApiResponse<PaymentStatusResponse> getPaymentStatus(
            @RequestHeader(value = "X-USER-ID", required = false) String userId,
            @PathVariable String paymentId) {

        // PG에서 최신 상태 확인 (Resilience 패턴 적용)
        OrderInfoDetail detail = orderFacade.getOrderInfoByPaymentId(userId, paymentId);
        try {
        } catch (Exception e) {
            System.out.println("PG 결제 상태 확인 실패 - paymentId: " + paymentId + ", 오류: " + e.getMessage());
        }

        return ApiResponse.success(PaymentStatusResponse.from(detail));
    }
}
