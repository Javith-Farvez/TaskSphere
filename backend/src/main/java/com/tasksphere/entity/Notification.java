package com.tasksphere.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Notification Entity — stores every in-app notification for
 * customers, providers, and admins.
 *
 * Types:
 *   BOOKING_*   — booking lifecycle (placed, confirmed, en-route, completed, cancelled)
 *   PAYMENT_*   — payment received, payout processed, refund issued
 *   PROVIDER_*  — new job request, KYC approved/rejected, rating received
 *   ADMIN_*     — new provider signup, suspicious activity, platform alerts
 *   SYSTEM_*    — maintenance, promotions, general announcements
 */
@Entity
@Table(name = "notifications", indexes = {
    @Index(name = "idx_notif_user",   columnList = "user_id"),
    @Index(name = "idx_notif_read",   columnList = "is_read"),
    @Index(name = "idx_notif_type",   columnList = "type"),
    @Index(name = "idx_notif_created",columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The recipient of this notification */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Type of notification — drives icon, colour, and routing */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    /** Short heading shown in notification bell */
    @Column(nullable = false)
    private String title;

    /** Full message body */
    @Column(nullable = false, length = 512)
    private String message;

    /** Optional reference ID (bookingId, paymentId, etc.) for deep-linking */
    private Long referenceId;

    /** Optional reference type label e.g. "BOOKING", "PAYMENT" */
    private String referenceType;

    /** Icon/emoji to display alongside the notification */
    @Builder.Default
    private String icon = "🔔";

    /** Colour category for the UI badge */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private NotificationColor color = NotificationColor.BLUE;

    /** Whether the user has read (opened) this notification */
    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    /** Timestamp when the user read it */
    private LocalDateTime readAt;

    /** Whether a push/email was also delivered */
    @Builder.Default
    private Boolean emailSent = false;

    @Builder.Default
    private Boolean pushSent = false;

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    // ── Mark as read ──────────────────────────────────────────────
    public void markRead() {
        this.isRead = true;
        this.readAt = LocalDateTime.now();
    }

    // ── Enums ─────────────────────────────────────────────────────
    public enum NotificationType {
        // Booking
        BOOKING_PLACED,
        BOOKING_CONFIRMED,
        BOOKING_EN_ROUTE,
        BOOKING_STARTED,
        BOOKING_COMPLETED,
        BOOKING_CANCELLED,
        BOOKING_RESCHEDULED,

        // Payment
        PAYMENT_RECEIVED,
        PAYMENT_FAILED,
        PAYMENT_REFUNDED,
        PAYOUT_PROCESSED,
        PAYOUT_FAILED,

        // Provider
        NEW_JOB_REQUEST,
        JOB_ASSIGNED,
        KYC_APPROVED,
        KYC_REJECTED,
        RATING_RECEIVED,
        PROFILE_VERIFIED,
        REVIEW_REPLY,

        // Admin
        NEW_PROVIDER_SIGNUP,
        PROVIDER_KYC_SUBMITTED,
        HIGH_VALUE_BOOKING,
        SUSPICIOUS_ACTIVITY,

        // System
        SYSTEM_MAINTENANCE,
        PROMOTION,
        GENERAL
    }

    public enum NotificationColor {
        BLUE, GREEN, RED, ORANGE, PURPLE, TEAL
    }
}
