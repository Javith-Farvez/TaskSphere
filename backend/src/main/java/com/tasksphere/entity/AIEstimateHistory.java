package com.tasksphere.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Stores every AI Cost Estimator run — used both as an audit trail
 * (Estimated Price History on the customer dashboard) and as the
 * "Historical Prices" data source the estimator itself learns from
 * on future runs (more saved estimates + completed bookings ⇒ higher
 * confidence %).
 */
@Entity
@Table(name = "ai_estimate_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AIEstimateHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Nullable — estimator can be used before login (landing page widget)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private User customer;

    @Column(nullable = false)
    private String serviceCategory;

    private String serviceType;

    @Column(length = 500)
    private String address;

    private Double customerLat;
    private Double customerLng;

    @Column(nullable = false)
    private Double distanceKm;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Urgency urgency = Urgency.NORMAL;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Complexity complexity = Complexity.MEDIUM;

    // ── Output: price breakdown ─────────────────────────────────
    @Column(nullable = false)
    private Double basePrice;
    private Double distanceCharge;
    private Double urgencyCharge;
    private Double complexityCharge;
    private Double platformFee;

    @Column(nullable = false)
    private Double estimatedPrice;

    @Column(nullable = false)
    private Integer estimatedDurationMinutes;

    @Column(nullable = false)
    private Double confidencePercent;

    // ── Output: provider matching ───────────────────────────────
    private Long recommendedProviderId;
    private String recommendedProviderName;
    private Double recommendedProviderRating;

    private Long nearestProviderId;
    private String nearestProviderName;
    private Double nearestProviderDistanceKm;

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum Urgency { NORMAL, URGENT, EMERGENCY }
    public enum Complexity { LOW, MEDIUM, HIGH }
}
