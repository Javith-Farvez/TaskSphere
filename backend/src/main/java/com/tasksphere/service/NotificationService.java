package com.tasksphere.service;

import com.tasksphere.dto.NotificationDtos.*;
import com.tasksphere.entity.*;
import com.tasksphere.entity.Notification.NotificationColor;
import com.tasksphere.entity.Notification.NotificationType;
import com.tasksphere.repository.NotificationRepository;
import com.tasksphere.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * NotificationService — central hub for creating and managing
 * all notifications across the TaskSphere platform.
 *
 * Triggered by:
 *  • BookingService  — booking lifecycle events
 *  • PaymentService  — payment/payout events
 *  • AdminController — KYC approvals, broadcasts
 *  • AuthService     — welcome notifications on signup
 */
@Service
public class NotificationService {

    @Autowired private NotificationRepository notifRepo;
    @Autowired private UserRepository         userRepo;
    @Autowired private SseEmitterRegistry     sseRegistry;

    // ═══════════════════════════════════════════════════════════════
    //  CORE BUILDER — all other methods call this
    // ═══════════════════════════════════════════════════════════════
    public Notification create(User recipient, NotificationType type,
                               String title, String message,
                               String icon, NotificationColor color,
                               Long refId, String refType) {
        Notification saved = notifRepo.save(Notification.builder()
            .user(recipient)
            .type(type)
            .title(title)
            .message(message)
            .icon(icon)
            .color(color)
            .referenceId(refId)
            .referenceType(refType)
            .isRead(false)
            .build());

        // Real-time push — instantly delivered to any open dashboard tab via SSE.
        // If the user has no live connection right now, they'll still see it
        // on next REST poll / login since it's already saved to MySQL above.
        if (recipient != null && recipient.getId() != null) {
            sseRegistry.send(recipient.getId(), "notification", NotificationResponse.from(saved));
        }

        return saved;
    }

    // ── Lightweight live-dashboard ping ──────────────────────────────
    // NOT a stored Notification (would spam the admin inbox with every
    // single booking) — just a bare SSE signal telling any open Admin
    // dashboard "something changed, re-fetch your numbers". This is what
    // makes the Overview KPIs/charts update themselves in real time as
    // customers register, bookings complete, etc., instead of needing a
    // manual page reload.
    public void pingAdminStatsRefresh(String reason) {
        userRepo.findByRole(User.Role.ADMIN).forEach(admin ->
            sseRegistry.send(admin.getId(), "stats-refresh", Map.of("reason", reason)));
    }

    // ═══════════════════════════════════════════════════════════════
    //  BOOKING NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════════

    /** Customer places a new booking → notify customer + provider */
    public void onBookingPlaced(Booking booking) {
        pingAdminStatsRefresh("booking_placed");
        // To customer
        create(booking.getCustomer(),
            NotificationType.BOOKING_PLACED,
            "Booking Confirmed! 🎉",
            "Your " + booking.getService() + " booking (#TS" + booking.getId() + ") has been placed. "
                + "We're finding the best provider for you.",
            "📅", NotificationColor.BLUE,
            booking.getId(), "BOOKING");

        // To provider (if already assigned)
        if (booking.getProvider() != null) {
            create(booking.getProvider(),
                NotificationType.NEW_JOB_REQUEST,
                "New Job Request! 🔔",
                booking.getCustomer().getName() + " booked " + booking.getService()
                    + " at " + (booking.getAddress() != null ? booking.getAddress() : "—")
                    + ". Slot: " + (booking.getSlot() != null ? booking.getSlot() : "ASAP")
                    + ". Amount: ₹" + String.format("%.0f", booking.getAmount()),
                "🔔", NotificationColor.ORANGE,
                booking.getId(), "BOOKING");
        } else {
            // Notify all available providers (simplified — in production use matching service)
            notifyAllProviders(
                NotificationType.NEW_JOB_REQUEST,
                "New Job Available! 🆕",
                booking.getService() + " job in " + (booking.getAddress() != null ? booking.getAddress() : "your area")
                    + " — ₹" + String.format("%.0f", booking.getAmount()) + ". Tap to accept.",
                "🆕", NotificationColor.ORANGE,
                booking.getId(), "BOOKING");
        }
    }

    /** Provider accepts the job */
    public void onBookingConfirmed(Booking booking) {
        String provName = booking.getProvider() != null ? booking.getProvider().getName() : "A provider";
        create(booking.getCustomer(),
            NotificationType.BOOKING_CONFIRMED,
            "Provider Confirmed ✅",
            provName + " has accepted your " + booking.getService() + " booking (#TS" + booking.getId() + ")."
                + " Scheduled: " + (booking.getSlot() != null ? booking.getSlot() : "Soon"),
            "✅", NotificationColor.GREEN,
            booking.getId(), "BOOKING");
    }

    /** Provider marks en-route */
    public void onProviderEnRoute(Booking booking) {
        String provName = booking.getProvider() != null ? booking.getProvider().getName() : "Your provider";
        create(booking.getCustomer(),
            NotificationType.BOOKING_EN_ROUTE,
            "Provider On The Way 🚗",
            provName + " is heading to your location for " + booking.getService()
                + ". Track their live location in the app.",
            "🚗", NotificationColor.TEAL,
            booking.getId(), "BOOKING");
    }

    /** Provider starts the job */
    public void onBookingStarted(Booking booking) {
        create(booking.getCustomer(),
            NotificationType.BOOKING_STARTED,
            "Service Started 🔧",
            "Your " + booking.getService() + " service has begun. "
                + "You'll be notified when it's complete.",
            "🔧", NotificationColor.BLUE,
            booking.getId(), "BOOKING");
    }

    /** Job completed */
    public void onBookingCompleted(Booking booking) {
        pingAdminStatsRefresh("booking_completed");
        // Customer
        create(booking.getCustomer(),
            NotificationType.BOOKING_COMPLETED,
            "Service Completed! ⭐",
            "Your " + booking.getService() + " (#TS" + booking.getId() + ") is done. "
                + "How did it go? Rate your experience now.",
            "⭐", NotificationColor.GREEN,
            booking.getId(), "BOOKING");

        // Provider
        if (booking.getProvider() != null) {
            create(booking.getProvider(),
                NotificationType.BOOKING_COMPLETED,
                "Job Completed 💰",
                booking.getService() + " for " + booking.getCustomer().getName()
                    + " marked complete. Payment of ₹" + String.format("%.0f", booking.getAmount())
                    + " will be credited after platform fee.",
                "💰", NotificationColor.GREEN,
                booking.getId(), "BOOKING");
        }
    }

    /** Booking rescheduled by the customer */
    public void onBookingRescheduled(Booking booking, String oldSlot) {
        create(booking.getCustomer(),
            NotificationType.BOOKING_RESCHEDULED,
            "Booking Rescheduled 📅",
            "Your " + booking.getService() + " booking (#TS" + booking.getId() + ") has been moved from "
                + (oldSlot != null ? oldSlot : "its original slot") + " to "
                + (booking.getSlot() != null ? booking.getSlot() : "a new time") + ".",
            "📅", NotificationColor.BLUE,
            booking.getId(), "BOOKING");

        if (booking.getProvider() != null) {
            create(booking.getProvider(),
                NotificationType.BOOKING_RESCHEDULED,
                "Job Rescheduled 📅",
                booking.getCustomer().getName() + " moved the " + booking.getService()
                    + " booking (#TS" + booking.getId() + ") to "
                    + (booking.getSlot() != null ? booking.getSlot() : "a new time") + ".",
                "📅", NotificationColor.BLUE,
                booking.getId(), "BOOKING");
        }
    }

    /** Booking cancelled */
    public void onBookingCancelled(Booking booking, String cancelledBy) {
        String byLabel = "CUSTOMER".equalsIgnoreCase(cancelledBy) ? "the customer" : "the provider";

        // Notify customer
        create(booking.getCustomer(),
            NotificationType.BOOKING_CANCELLED,
            "Booking Cancelled ❌",
            "Your " + booking.getService() + " booking (#TS" + booking.getId() + ") was cancelled by "
                + byLabel + ". Refund will be processed within 3–5 business days.",
            "❌", NotificationColor.RED,
            booking.getId(), "BOOKING");

        // Notify provider if assigned
        if (booking.getProvider() != null) {
            create(booking.getProvider(),
                NotificationType.BOOKING_CANCELLED,
                "Job Cancelled ❌",
                booking.getService() + " booking by " + booking.getCustomer().getName()
                    + " (#TS" + booking.getId() + ") has been cancelled.",
                "❌", NotificationColor.RED,
                booking.getId(), "BOOKING");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  PAYMENT NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════════

    /** Payment successfully received */
    public void onPaymentReceived(Payment payment) {
        create(payment.getProvider(),
            NotificationType.PAYMENT_RECEIVED,
            "Payment Received 💳",
            "₹" + String.format("%.0f", payment.getNetAmount()) + " credited for "
                + (payment.getServiceName() != null ? payment.getServiceName() : "service")
                + " (after ₹" + String.format("%.0f", payment.getPlatformFee()) + " platform fee)."
                + " Ref: " + payment.getRazorpayRef(),
            "💳", NotificationColor.GREEN,
            payment.getId(), "PAYMENT");
    }

    /** Payment failed */
    public void onPaymentFailed(Payment payment) {
        create(payment.getProvider(),
            NotificationType.PAYMENT_FAILED,
            "Payment Failed ⚠️",
            "Payment of ₹" + String.format("%.0f", payment.getAmount()) + " for "
                + (payment.getServiceName() != null ? payment.getServiceName() : "service")
                + " failed. The customer will be asked to retry.",
            "⚠️", NotificationColor.RED,
            payment.getId(), "PAYMENT");

        // Also notify booking customer if booking attached
        if (payment.getBooking() != null) {
            create(payment.getBooking().getCustomer(),
                NotificationType.PAYMENT_FAILED,
                "Payment Failed — Action Required ⚠️",
                "Your payment of ₹" + String.format("%.0f", payment.getAmount())
                    + " could not be processed. Please retry to confirm your booking.",
                "⚠️", NotificationColor.RED,
                payment.getId(), "PAYMENT");
        }
    }

    /** Refund issued to customer */
    public void onRefundIssued(Booking booking) {
        create(booking.getCustomer(),
            NotificationType.PAYMENT_REFUNDED,
            "Refund Initiated 💸",
            "₹" + String.format("%.0f", booking.getAmount()) + " refund for "
                + booking.getService() + " (#TS" + booking.getId() + ") has been initiated. "
                + "Expect credit within 3–5 business days.",
            "💸", NotificationColor.TEAL,
            booking.getId(), "BOOKING");
    }

    /** Payout processed to provider */
    public void onPayoutProcessed(User provider, double amount, String bank, String ref) {
        create(provider,
            NotificationType.PAYOUT_PROCESSED,
            "Payout Processed 🏦",
            "₹" + String.format("%.0f", amount) + " payout to " + bank + " is on its way. "
                + "Ref: " + ref + ". Expect credit within 1–2 business days.",
            "🏦", NotificationColor.GREEN,
            null, "PAYOUT");
    }

    /** Payout failed */
    public void onPayoutFailed(User provider, double amount, String bank) {
        create(provider,
            NotificationType.PAYOUT_FAILED,
            "Payout Failed ❌",
            "Payout of ₹" + String.format("%.0f", amount) + " to " + bank
                + " failed. Please verify your bank details in Settings.",
            "❌", NotificationColor.RED,
            null, "PAYOUT");
    }

    // ═══════════════════════════════════════════════════════════════
    //  PROVIDER NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════════

    /** KYC / ID proof approved by admin */
    public void onKycApproved(User provider) {
        create(provider,
            NotificationType.KYC_APPROVED,
            "Identity Verified ✅",
            "Congratulations! Your ID proof has been verified. "
                + "Your profile now shows a verified badge and you can accept more jobs.",
            "✅", NotificationColor.GREEN,
            provider.getId(), "USER");
    }

    /** KYC rejected */
    public void onKycRejected(User provider, String reason) {
        create(provider,
            NotificationType.KYC_REJECTED,
            "ID Verification Failed ❌",
            "Your ID proof was not accepted" + (reason != null ? ": " + reason : ".")
                + " Please re-upload a clear, valid government ID.",
            "❌", NotificationColor.RED,
            provider.getId(), "USER");
    }

    /** Customer left a rating */
    public void onRatingReceived(User provider, double rating, String customerName, String service) {
        create(provider,
            NotificationType.RATING_RECEIVED,
            "New Rating: " + String.format("%.1f", rating) + "⭐",
            customerName + " rated your " + service + " " + String.format("%.1f", rating)
                + " stars. Keep up the great work!",
            "⭐", NotificationColor.ORANGE,
            null, "REVIEW");
    }

    /** Provider profile verified by admin */
    public void onProfileVerified(User provider) {
        create(provider,
            NotificationType.PROFILE_VERIFIED,
            "Profile Verified 🎉",
            "Your TaskSphere provider profile has been fully verified. "
                + "You'll now appear higher in customer searches.",
            "🎉", NotificationColor.TEAL,
            provider.getId(), "USER");
    }

    /** Provider replied to a customer's review */
    public void onReviewReply(User customer, String providerName, Long reviewId) {
        create(customer,
            NotificationType.REVIEW_REPLY,
            "Provider Replied to Your Review 💬",
            providerName + " replied to the review you left. Tap to see what they said.",
            "💬", NotificationColor.BLUE,
            reviewId, "REVIEW");
    }

    // ═══════════════════════════════════════════════════════════════
    //  ADMIN NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════════

    /** New provider registered — alert all admins */
    public void onNewProviderSignup(User newProvider) {
        userRepo.findByRole(User.Role.ADMIN).forEach(admin ->
            create(admin,
                NotificationType.NEW_PROVIDER_SIGNUP,
                "New Provider Signup 🆕",
                newProvider.getName() + " (" + newProvider.getEmail() + ") registered as a provider. "
                    + "Review and verify their profile.",
                "🆕", NotificationColor.BLUE,
                newProvider.getId(), "USER")
        );
    }

    /** Provider submitted KYC documents */
    public void onKycSubmitted(User provider) {
        userRepo.findByRole(User.Role.ADMIN).forEach(admin ->
            create(admin,
                NotificationType.PROVIDER_KYC_SUBMITTED,
                "KYC Submitted for Review 🪪",
                provider.getName() + " submitted an ID document for verification. "
                    + "Please review and approve/reject in the admin panel.",
                "🪪", NotificationColor.ORANGE,
                provider.getId(), "USER")
        );
    }

    /** High-value booking alert */
    public void onHighValueBooking(Booking booking) {
        if (booking.getAmount() < 2000) return; // only for high-value
        userRepo.findByRole(User.Role.ADMIN).forEach(admin ->
            create(admin,
                NotificationType.HIGH_VALUE_BOOKING,
                "High-Value Booking ₹" + String.format("%.0f", booking.getAmount()) + " 💰",
                booking.getCustomer().getName() + " booked " + booking.getService()
                    + " for ₹" + String.format("%.0f", booking.getAmount()) + ". Monitoring.",
                "💰", NotificationColor.PURPLE,
                booking.getId(), "BOOKING")
        );
    }

    // ═══════════════════════════════════════════════════════════════
    //  WELCOME / SYSTEM
    // ═══════════════════════════════════════════════════════════════

    public void onWelcome(User user) {
        pingAdminStatsRefresh("new_registration");
        String msg = user.getRole() == User.Role.PROVIDER
            ? "Welcome to TaskSphere, " + user.getName().split(" ")[0] + "! Complete your profile and start accepting jobs today. 🔧"
            : "Welcome to TaskSphere, " + user.getName().split(" ")[0] + "! Book your first service in seconds. 🎉";
        create(user,
            NotificationType.GENERAL,
            "Welcome to TaskSphere! 👋",
            msg, "👋", NotificationColor.TEAL, null, null);
    }

    // ── Admin broadcast to all or a role ─────────────────────────
    public int broadcastToRole(String role, String title, String message, String icon) {
        List<User> targets;
        if ("CUSTOMER".equalsIgnoreCase(role)) {
            targets = userRepo.findByRole(User.Role.CUSTOMER);
        } else if ("PROVIDER".equalsIgnoreCase(role)) {
            targets = userRepo.findByRole(User.Role.PROVIDER);
        } else {
            targets = userRepo.findAll();
        }
        targets.forEach(u -> create(u,
            NotificationType.GENERAL, title, message,
            icon != null ? icon : "📢", NotificationColor.BLUE, null, null));
        return targets.size();
    }

    // ── Internal: notify all available providers ──────────────────
    private void notifyAllProviders(NotificationType type, String title, String message,
                                    String icon, NotificationColor color,
                                    Long refId, String refType) {
        userRepo.findByRole(User.Role.PROVIDER).forEach(p ->
            create(p, type, title, message, icon, color, refId, refType));
    }

    // ═══════════════════════════════════════════════════════════════
    //  READ / FETCH APIs (called by controller)
    // ═══════════════════════════════════════════════════════════════

    public List<NotificationResponse> getForUser(User user) {
        return notifRepo.findByUserOrderByCreatedAtDesc(user, PageRequest.of(0, 50))
                .stream().map(NotificationResponse::from).toList();
    }

    public NotificationSummary getSummary(User user) {
        long unread = notifRepo.countUnreadByUser(user);
        long total  = notifRepo.findByUserOrderByCreatedAtDesc(user).size();
        List<NotificationResponse> recent = notifRepo
            .findByUserOrderByCreatedAtDesc(user, PageRequest.of(0, 10))
            .stream().map(NotificationResponse::from).toList();
        return new NotificationSummary(unread, total, recent);
    }

    public int markAllRead(User user) {
        return notifRepo.markAllReadByUser(user, LocalDateTime.now());
    }

    public boolean markOneRead(Long id, User user) {
        return notifRepo.markOneRead(id, user, LocalDateTime.now()) > 0;
    }

    public void deleteOne(Long id, User user) {
        notifRepo.findById(id).ifPresent(n -> {
            if (n.getUser().getId().equals(user.getId())) notifRepo.delete(n);
        });
    }

    // ── Scheduled cleanup: delete read notifs older than 30 days ──
    @Scheduled(cron = "0 0 3 * * *")  // runs daily at 3 AM
    public void cleanupOldNotifications() {
        int deleted = notifRepo.deleteOldRead(LocalDateTime.now().minusDays(30));
        System.out.println("[NotifCleanup] Deleted " + deleted + " old read notifications.");
    }
}
