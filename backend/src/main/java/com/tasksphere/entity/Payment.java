package com.tasksphere.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Payment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id")
    private User provider;

    // Nullable — set for payments recorded before a provider/booking is
    // finalized (order-creation placeholder, failed/abandoned checkout),
    // so webhooks and failure callbacks can still notify the right person.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @Column(nullable = false, unique = true)
    private String razorpayRef;        // razorpay_payment_id or payout ref

    private String razorpayOrderId;
    private String razorpaySignature;

    @Column(nullable = false)
    private Double amount;

    @Column(nullable = false)
    private Double platformFee;

    @Column(nullable = false)
    private Double netAmount;

    @Column(nullable = false)
    private String paymentMethod;      // UPI, Card, NB, Razorpay X

    private String customerName;
    private String serviceName;
    private String note;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PaymentType type = PaymentType.CREDIT;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PAID;

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum PaymentType  { CREDIT, PAYOUT }
    public enum PaymentStatus { PAID, PENDING, FAILED, REFUNDED }
}
