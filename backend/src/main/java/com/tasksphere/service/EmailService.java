package com.tasksphere.service;

import com.tasksphere.entity.Booking;
import com.tasksphere.entity.Payment;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

/**
 * EmailService — sends transactional emails via Spring Mail.
 *
 * Feature-flagged with `app.mail.enabled` so the app runs fine out of the
 * box even without real SMTP credentials configured: every public method
 * checks the flag first and simply logs + returns if disabled, instead of
 * throwing. This means booking/payment/auth flows NEVER fail because of
 * email — email is always a best-effort side-effect, never a blocker.
 */
@Service
@Slf4j
public class EmailService {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${app.mail.from:TaskSphere <no-reply@tasksphere.in>}")
    private String fromAddress;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");
    private static final String BRAND = "#6c5ce7";

    // ══════════════════════════════════════════════════════════════
    //  1. BOOKING CONFIRMATION EMAIL
    // ══════════════════════════════════════════════════════════════
    public void sendBookingConfirmation(Booking booking) {
        if (booking.getCustomer() == null) return;
        String name    = booking.getCustomer().getName();
        String to      = booking.getCustomer().getEmail();
        String service = booking.getService() != null ? booking.getService() : "your service";
        String slot    = booking.getSlot() != null ? booking.getSlot() : "to be scheduled";
        String address = booking.getAddress() != null ? booking.getAddress() : "—";
        String provider= booking.getProvider() != null ? booking.getProvider().getName() : "Matching you with a pro now";

        String body = wrapper(
            "Booking Confirmed 🎉",
            "Hi " + firstName(name) + ",",
            "Your booking for <strong>" + esc(service) + "</strong> is confirmed. Here are the details:",
            rows(
                row("Booking ID", "#TS" + booking.getId()),
                row("Service", esc(service)),
                row("Provider", esc(provider)),
                row("Scheduled", esc(slot)),
                row("Address", esc(address)),
                row("Amount", "₹" + fmt(booking.getAmount()))
            ),
            note(booking.getPaymentStatus() == Booking.PaymentStatus.PENDING
                ? "This is a Cash on Delivery booking — please keep ₹" + fmt(booking.getAmount())
                    + " ready to pay your provider in cash once the job is done. You'll get a payment receipt then."
                : "We'll notify you as soon as your provider is on the way. You can track everything from your TaskSphere dashboard.")
        );
        send(to, "✅ Booking Confirmed — #TS" + booking.getId(), body);
    }

    // ══════════════════════════════════════════════════════════════
    //  2. PAYMENT RECEIPT EMAIL — to the customer (booking payment)
    // ══════════════════════════════════════════════════════════════
    public void sendCustomerPaymentReceipt(Booking booking) {
        if (booking.getCustomer() == null) return;
        String to   = booking.getCustomer().getEmail();
        String name = booking.getCustomer().getName();

        String body = wrapper(
            "Payment Receipt 🧾",
            "Hi " + firstName(name) + ",",
            "Thanks for your payment. Here's your receipt for booking <strong>#TS" + booking.getId() + "</strong>:",
            rows(
                row("Service", esc(booking.getService())),
                row("Amount Paid", "₹" + fmt(booking.getAmount())),
                row("Payment Method", esc(booking.getPaymentMethod() != null ? booking.getPaymentMethod() : "Razorpay")),
                row("Reference", esc(booking.getPaymentRef())),
                row("Status", esc(booking.getPaymentStatus().name())),
                row("Date", booking.getCreatedAt() != null ? booking.getCreatedAt().format(DATE_FMT) : "—")
            ),
            note("Keep this email for your records. Need a refund or have a billing question? Just reach out to TaskSphere support.")
        );
        send(to, "🧾 Payment Receipt — ₹" + fmt(booking.getAmount()), body);
    }

    // ══════════════════════════════════════════════════════════════
    //  3. PAYMENT RECEIPT EMAIL — to the provider (earnings credited)
    // ══════════════════════════════════════════════════════════════
    public void sendProviderEarningsReceipt(Payment payment) {
        if (payment.getProvider() == null) return;
        String to   = payment.getProvider().getEmail();
        String name = payment.getProvider().getName();

        String body = wrapper(
            "Payment Receipt 💰",
            "Hi " + firstName(name) + ",",
            "You've been paid! Here's your receipt:",
            rows(
                row("Service", esc(payment.getServiceName() != null ? payment.getServiceName() : "Service")),
                row("Customer", esc(payment.getCustomerName() != null ? payment.getCustomerName() : "—")),
                row("Gross Amount", "₹" + fmt(payment.getAmount())),
                row("Platform Fee", "₹" + fmt(payment.getPlatformFee())),
                row("Net Credited", "₹" + fmt(payment.getNetAmount())),
                row("Reference", esc(payment.getRazorpayRef())),
                row("Date", payment.getCreatedAt() != null ? payment.getCreatedAt().format(DATE_FMT) : "—")
            ),
            note("This amount has been added to your TaskSphere wallet and is available for payout to your bank account.")
        );
        send(to, "💰 Payment Received — ₹" + fmt(payment.getNetAmount()), body);
    }

    // ══════════════════════════════════════════════════════════════
    //  4. OTP EMAIL — password reset
    // ══════════════════════════════════════════════════════════════
    public void sendOtpEmail(String toEmail, String name, String otp, String purpose) {
        String body = wrapper(
            "Your Verification Code 🔐",
            "Hi " + firstName(name) + ",",
            "Use the code below to " + esc(purpose) + ". This code expires in <strong>10 minutes</strong>.",
            "<div style=\"text-align:center;margin:24px 0;\">"
                + "<span style=\"display:inline-block;background:#f4f3ff;color:" + BRAND + ";"
                + "font-size:32px;font-weight:800;letter-spacing:8px;padding:14px 28px;border-radius:10px;\">"
                + esc(otp) + "</span></div>",
            note("If you didn't request this code, you can safely ignore this email — your account is still secure.")
        );
        send(toEmail, "🔐 Your TaskSphere verification code: " + otp, body);
    }

    // ══════════════════════════════════════════════════════════════
    //  5. BOOKING CANCELLED EMAIL
    // ══════════════════════════════════════════════════════════════
    public void sendBookingCancelledEmail(Booking booking, String cancelledBy) {
        if (booking.getCustomer() == null) return;
        String to   = booking.getCustomer().getEmail();
        String name = booking.getCustomer().getName();
        String byLabel = "PROVIDER".equalsIgnoreCase(cancelledBy) ? "your provider" : "you";

        String body = wrapper(
            "Booking Cancelled ❌",
            "Hi " + firstName(name) + ",",
            "Your booking <strong>#TS" + booking.getId() + "</strong> for "
                + esc(booking.getService()) + " was cancelled by " + byLabel + ".",
            rows(
                row("Service", esc(booking.getService())),
                row("Amount", "₹" + fmt(booking.getAmount())),
                row("Refund Status", esc(booking.getPaymentStatus().name())),
                row("Reference", esc(booking.getPaymentRef()))
            ),
            note("If a payment was made, your refund of ₹" + fmt(booking.getAmount())
                + " will be credited back to your original payment method within 3–5 business days.")
        );
        send(to, "❌ Booking Cancelled — #TS" + booking.getId(), body);
    }

    // ══════════════════════════════════════════════════════════════
    //  6. BOOKING RESCHEDULED EMAIL
    // ══════════════════════════════════════════════════════════════
    public void sendBookingRescheduledEmail(Booking booking, String oldSlot) {
        if (booking.getCustomer() == null) return;
        String to   = booking.getCustomer().getEmail();
        String name = booking.getCustomer().getName();

        String body = wrapper(
            "Booking Rescheduled 📅",
            "Hi " + firstName(name) + ",",
            "Your booking <strong>#TS" + booking.getId() + "</strong> for "
                + esc(booking.getService()) + " has a new time:",
            rows(
                row("Service", esc(booking.getService())),
                row("Previous Slot", esc(oldSlot != null ? oldSlot : "—")),
                row("New Slot", esc(booking.getSlot())),
                row("Provider", esc(booking.getProvider() != null ? booking.getProvider().getName() : "To be assigned"))
            ),
            note("No action needed — your provider has been notified of the change.")
        );
        send(to, "📅 Booking Rescheduled — #TS" + booking.getId(), body);
    }

    // ══════════════════════════════════════════════════════════════
    //  CORE SEND (best-effort — never throws)
    // ══════════════════════════════════════════════════════════════
    private void send(String to, String subject, String htmlBody) {
        if (!mailEnabled) {
            log.info("[EmailService] Mail disabled (app.mail.enabled=false) — skipped '{}' to {}", subject, to);
            return;
        }
        if (mailSender == null) {
            log.warn("[EmailService] No JavaMailSender bean available — skipped '{}' to {}", subject, to);
            return;
        }
        if (to == null || to.isBlank()) return;
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("[EmailService] Sent '{}' to {}", subject, to);
        } catch (MessagingException | RuntimeException e) {
            // Email failures must never break the booking/payment/auth flow that triggered them.
            log.error("[EmailService] Failed to send '{}' to {}: {}", subject, to, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  TEMPLATE HELPERS
    // ══════════════════════════════════════════════════════════════
    private String wrapper(String heading, String greeting, String intro, String... blocks) {
        StringBuilder middle = new StringBuilder();
        for (String b : blocks) middle.append(b);
        return "<!DOCTYPE html><html><body style=\"margin:0;padding:0;background:#f2f1f8;"
            + "font-family:'Segoe UI',Helvetica,Arial,sans-serif;\">"
            + "<div style=\"max-width:520px;margin:0 auto;padding:32px 16px;\">"
            + "<div style=\"background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,.06);\">"
            + "<div style=\"background:" + BRAND + ";padding:22px 28px;\">"
            + "<span style=\"color:#fff;font-size:20px;font-weight:800;letter-spacing:.3px;\">TaskSphere</span>"
            + "</div>"
            + "<div style=\"padding:28px 28px 8px;\">"
            + "<h2 style=\"margin:0 0 16px;color:#1a1a2e;font-size:21px;\">" + esc(heading) + "</h2>"
            + "<p style=\"margin:0 0 6px;color:#444;font-size:15px;\">" + esc(greeting) + "</p>"
            + "<p style=\"margin:0 0 4px;color:#444;font-size:15px;line-height:1.5;\">" + intro + "</p>"
            + middle
            + "</div>"
            + "<div style=\"background:#fafafa;padding:18px 28px;border-top:1px solid #eee;\">"
            + "<p style=\"margin:0;color:#999;font-size:12px;\">TaskSphere · On-demand home services · This is an automated message, please don't reply.</p>"
            + "</div>"
            + "</div></div></body></html>";
    }

    private String rows(String... rows) {
        StringBuilder sb = new StringBuilder("<div style=\"margin:18px 0;border:1px solid #eee;border-radius:10px;overflow:hidden;\">");
        for (String r : rows) sb.append(r);
        sb.append("</div>");
        return sb.toString();
    }

    private String row(String label, String value) {
        return "<div style=\"display:flex;justify-content:space-between;padding:10px 16px;border-bottom:1px solid #f0f0f0;font-size:14px;\">"
            + "<span style=\"color:#888;\">" + esc(label) + "</span>"
            + "<span style=\"color:#1a1a2e;font-weight:600;\">" + value + "</span></div>";
    }

    private String note(String text) {
        return "<p style=\"margin:14px 0 6px;color:#777;font-size:13px;line-height:1.5;\">" + esc(text) + "</p>";
    }

    private String fmt(Double amount) { return amount != null ? String.format("%.0f", amount) : "0"; }
    private String firstName(String name) { return (name == null || name.isBlank()) ? "there" : name.trim().split(" ")[0]; }
    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
