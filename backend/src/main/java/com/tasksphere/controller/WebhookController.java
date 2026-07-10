package com.tasksphere.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasksphere.entity.Notification;
import com.tasksphere.entity.Payment;
import com.tasksphere.repository.PaymentRepository;
import com.tasksphere.service.NotificationService;
import com.tasksphere.service.RazorpayService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Razorpay Webhook receiver — the authoritative, server-to-server "Payment
 * Success Callback" / "Payment Failure Callback". Unlike the client-side
 * checkout handler (which can be interrupted by a closed tab or bad network
 * right after a successful charge), Razorpay calls THIS endpoint directly
 * from their servers whenever a payment's real status changes, so it's the
 * source of truth for whether money actually moved.
 *
 * Setup: Razorpay Dashboard → Settings → Webhooks → add this URL
 * (https://yourdomain.com/api/webhooks/razorpay), select events
 * payment.captured, payment.failed, refund.processed, and paste the
 * generated secret into razorpay.webhook.secret in application.properties.
 * Until that's done, isWebhookConfigured() is false and this endpoint
 * safely rejects requests instead of trusting unverified callers.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    @Autowired private RazorpayService     razorpayService;
    @Autowired private PaymentRepository   paymentRepo;
    @Autowired private NotificationService notifService;
    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping("/razorpay")
    public ResponseEntity<?> razorpayWebhook(@RequestBody String rawBody,
                                             @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        if (!razorpayService.isWebhookConfigured()) {
            // Honest response — nothing to verify against yet, so we cannot
            // safely trust this call. Configure razorpay.webhook.secret to activate.
            return ResponseEntity.status(503).body(Map.of("message", "Webhook not configured yet"));
        }
        if (!razorpayService.verifyWebhookSignature(rawBody, signature)) {
            return ResponseEntity.status(400).body(Map.of("message", "Invalid webhook signature"));
        }

        try {
            JsonNode root = mapper.readTree(rawBody);
            String event = root.path("event").asText("");

            switch (event) {
                case "payment.captured" -> handleCaptured(root);
                case "payment.failed"   -> handleFailed(root);
                case "refund.processed" -> handleRefundProcessed(root);
                default -> { /* other event types are acknowledged but not acted on */ }
            }
            return ResponseEntity.ok(Map.of("received", true));
        } catch (Exception e) {
            // Still 200 so Razorpay doesn't hammer retries on a parse issue,
            // but nothing was silently trusted — signature was already verified above.
            return ResponseEntity.ok(Map.of("received", true, "warning", "processing_error"));
        }
    }

    private void handleCaptured(JsonNode root) {
        JsonNode entity = root.path("payload").path("payment").path("entity");
        String paymentId = entity.path("id").asText(null);
        String orderId = entity.path("order_id").asText(null);
        if (orderId == null) return;

        paymentRepo.findByRazorpayOrderId(orderId).ifPresent(p -> {
            if (p.getStatus() == Payment.PaymentStatus.PAID) return; // already handled by success-callback — idempotent
            p.setRazorpayRef(paymentId != null ? paymentId : p.getRazorpayRef());
            p.setStatus(Payment.PaymentStatus.PAID);
            paymentRepo.save(p);

            if (p.getProvider() != null) {
                notifService.onPaymentReceived(p);
            } else if (p.getCustomer() != null) {
                notifService.create(p.getCustomer(), Notification.NotificationType.PAYMENT_RECEIVED,
                        "Payment Confirmed 💳",
                        "Your payment of ₹" + String.format("%.0f", p.getAmount()) + " was received. Ref: " + p.getRazorpayRef(),
                        "💳", Notification.NotificationColor.GREEN, p.getId(), "PAYMENT");
            }
        });
    }

    private void handleFailed(JsonNode root) {
        JsonNode entity = root.path("payload").path("payment").path("entity");
        String orderId = entity.path("order_id").asText(null);
        String errorDesc = entity.path("error_description").asText("Payment failed");
        if (orderId == null) return;

        paymentRepo.findByRazorpayOrderId(orderId).ifPresent(p -> {
            if (p.getStatus() == Payment.PaymentStatus.FAILED) return; // idempotent
            p.setStatus(Payment.PaymentStatus.FAILED);
            p.setNote(errorDesc);
            paymentRepo.save(p);

            if (p.getProvider() != null) {
                notifService.onPaymentFailed(p);
            } else if (p.getCustomer() != null) {
                notifService.create(p.getCustomer(), Notification.NotificationType.PAYMENT_FAILED,
                        "Payment Failed ⚠️",
                        "Your payment of ₹" + String.format("%.0f", p.getAmount()) + " failed: " + errorDesc,
                        "⚠️", Notification.NotificationColor.RED, p.getId(), "PAYMENT");
            }
        });
    }

    private void handleRefundProcessed(JsonNode root) {
        JsonNode entity = root.path("payload").path("refund").path("entity");
        String paymentId = entity.path("payment_id").asText(null);
        if (paymentId == null) return;

        paymentRepo.findByRazorpayRef(paymentId).ifPresent(p -> {
            if (p.getStatus() == Payment.PaymentStatus.REFUNDED) return; // idempotent
            p.setStatus(Payment.PaymentStatus.REFUNDED);
            paymentRepo.save(p);
            if (p.getBooking() != null) {
                notifService.onRefundIssued(p.getBooking());
            }
        });
    }
}
