package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.vo.Price;
import com.loopers.domain.common.vo.DiscountResult;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.*;
import lombok.Getter;

import java.util.List;

@Entity
@Table(name = "tb_order")
@Getter
public class Order extends BaseEntity {
    private Long userId;
    @ElementCollection
    @CollectionTable(
            name = "tb_order_item",
            joinColumns = @JoinColumn(name = "order_id")
    )
    private List<OrderItem> orderItems;
    @Convert(converter = Price.Converter.class)
    private Price originalPrice;
    private Long couponId;
    @Convert(converter = Price.Converter.class)
    private Price discountAmount;
    @Convert(converter = Price.Converter.class)
    private Price finalPrice;

    private String pgPaymentId; // 외부 결제 시스템에서 발급한 결제 ID
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    @Enumerated(EnumType.STRING)
    private PaymentFailureReason paymentFailureReason; // 결제 실패 사유
    private String cardType;  // SAMSUNG, etc.
    private String cardNo;  // 마스킹된 카드 번호
    private String callbackUrl; // 결제 완료 후 호출할 콜백 URL


    protected Order() {
    }

    private Order(Long userId, List<OrderItem> orderItems, Price originalPrice, Long couponId, Price discountAmount, Price finalPrice) {
        if (orderItems == null || orderItems.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 항목은 최소 1개 이상이어야 합니다.");
        }
        if (userId == null || userId <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용자 ID는 1 이상이어야 합니다.");
        }
        if (couponId != null && discountAmount.amount() > originalPrice.amount()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "할인 금액이 원가를 초과할 수 없습니다.");
        }
        this.userId = userId;
        this.orderItems = orderItems;
        this.originalPrice = originalPrice;
        this.couponId = couponId;
        this.discountAmount = discountAmount;
        this.finalPrice = finalPrice;
    }

    public static Order create(Long userId, List<OrderItem> orderItems, DiscountResult discountResult) {
        if (discountResult == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "할인 결과는 null일 수 없습니다.");
        }
        return new Order(
                userId,
                orderItems,
                discountResult.originalPrice(),
                discountResult.couponId(),
                discountResult.discountAmount(),
                discountResult.finalPrice()
        );
    }


    public static Order create(Long userId, List<OrderItem> orderItems) {
        if (orderItems == null || orderItems.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 항목은 최소 1개 이상이어야 합니다.");
        }
        Price originalPrice = new Price(orderItems.stream().mapToInt(OrderItem::getTotalPrice).sum());
        return create(userId, orderItems, new DiscountResult(originalPrice));
    }

    public void setPaymentInfo(String cardType, String cardNo, String callbackUrl) {
        this.cardType = cardType;
        this.cardNo = cardNo;
        this.callbackUrl = callbackUrl;
        this.status = OrderStatus.PENDING;
    }

    public void updateOrderStatus(OrderStatus newStatus, String pgPaymentId) {
        if (newStatus == OrderStatus.PAID && this.status == OrderStatus.PAID) {
            throw new CoreException(ErrorType.BAD_REQUEST, "이미 결제된 주문입니다.");
        }
        this.status = newStatus;
        this.pgPaymentId = pgPaymentId;
    }

    public void markAsPaymentFailed(PaymentFailureReason reason) {
        if (this.status == OrderStatus.PAID) {
            throw new CoreException(ErrorType.BAD_REQUEST, "이미 결제된 주문입니다.");
        }
        this.status = OrderStatus.PENDING;
        this.paymentFailureReason = reason;
    }
}
