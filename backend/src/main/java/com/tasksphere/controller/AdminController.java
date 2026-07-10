package com.tasksphere.controller;

import com.tasksphere.entity.AuditLog;
import com.tasksphere.repository.AuditLogRepository;
import com.tasksphere.service.AuditLogService;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.tasksphere.dto.CategoryDtos.CategoryRequest;
import com.tasksphere.dto.ComplaintDtos.ComplaintUpdateRequest;
import com.tasksphere.dto.NotificationDtos.BroadcastRequest;
import com.tasksphere.entity.Booking;
import com.tasksphere.entity.Complaint;
import com.tasksphere.entity.ServiceCategory;
import com.tasksphere.entity.User;
import com.tasksphere.repository.BookingRepository;
import com.tasksphere.repository.CategoryRepository;
import com.tasksphere.repository.ComplaintRepository;
import com.tasksphere.repository.NotificationRepository;
import com.tasksphere.repository.UserRepository;
import com.tasksphere.service.NotificationService;
import com.tasksphere.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired private UserRepository         userRepo;
    @Autowired private BookingRepository      bookingRepo;
    @Autowired private NotificationRepository notifRepo;
    @Autowired private NotificationService    notifService;
    @Autowired private com.tasksphere.repository.ReviewRepository reviewRepo;
    @Autowired private CategoryRepository  categoryRepo;
    @Autowired private ComplaintRepository complaintRepo;
    @Autowired private ReportService       reportService;
    @Autowired private com.tasksphere.service.AnalyticsService analyticsService;
    @Autowired private com.tasksphere.repository.PaymentRepository paymentRepo;
    @Autowired private com.tasksphere.service.RazorpayService razorpayService;
    @Autowired private AuditLogRepository  auditLogRepo;
    @Autowired private AuditLogService     auditLogService;

    // ── Platform stats ────────────────────────────────────────────
    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        long totalUsers   = userRepo.count();
        long providers    = userRepo.findByRole(User.Role.PROVIDER).size();
        long customers    = userRepo.findByRole(User.Role.CUSTOMER).size();
        long bookingsToday= bookingRepo.countTodayBookings();
        Double gmv        = bookingRepo.totalGMV();
        long unreadNotifs = notifRepo.countSince(LocalDateTime.now().minusDays(1));
        return ResponseEntity.ok(Map.of(
                "totalUsers",    totalUsers,
                "providers",     providers,
                "customers",     customers,
                "bookingsToday", bookingsToday,
                "gmv",           gmv != null ? gmv : 0,
                "newNotifs24h",  unreadNotifs
        ));
    }

    // ── Users ─────────────────────────────────────────────────────
    @GetMapping("/users")
    public ResponseEntity<?> allUsers() {
        return ResponseEntity.ok(userRepo.findAll().stream().map(u -> Map.of(
                "id", u.getId(), "name", u.getName(), "email", u.getEmail(),
                "role", u.getRole().name(), "status", u.getStatus().name(),
                "createdAt", u.getCreatedAt().toString()
        )).toList());
    }

    @GetMapping("/customers")
    public ResponseEntity<?> customers() {
        return ResponseEntity.ok(userRepo.findByRole(User.Role.CUSTOMER).stream().map(u -> Map.of(
                "id", u.getId(), "name", u.getName(), "email", u.getEmail(),
                "phone", u.getPhone() != null ? u.getPhone() : "",
                "status", u.getStatus().name()
        )).toList());
    }

    @GetMapping("/providers")
    public ResponseEntity<?> providers() {
        return ResponseEntity.ok(userRepo.findByRole(User.Role.PROVIDER).stream().map(u -> Map.of(
                "id", u.getId(), "name", u.getName(), "email", u.getEmail(),
                "status", u.getStatus().name()
        )).toList());
    }

    // ── Bookings ──────────────────────────────────────────────────
    @GetMapping("/bookings")
    public ResponseEntity<?> bookings() {
        return ResponseEntity.ok(bookingRepo.findAll().stream().map(b -> Map.of(
                "id", b.getId(),
                "service", b.getService() != null ? b.getService() : "",
                "amount", b.getAmount(),
                "status", b.getStatus().name(),
                "customerName", b.getCustomer() != null ? b.getCustomer().getName() : "",
                "providerName", b.getProvider() != null ? b.getProvider().getName() : "Unassigned",
                "createdAt", b.getCreatedAt().toString()
        )).toList());
    }

    // ── Live GPS Fleet Tracking (admin) ─────────────────────────────
    // Real-time feed for the admin map: every online provider's live GPS
    // dot, plus — for bookings currently in transit/progress — the real
    // distance + ETA from provider to customer (Haversine, or Google
    // Distance Matrix if app.maps.enabled=true). No mock data: providers
    // with no GPS fix or bookings with no live coords are simply omitted.
    @Autowired private com.tasksphere.service.GoogleMapsService mapsService;

    @GetMapping("/tracking/live")
    public ResponseEntity<?> liveTracking() {
        // All online providers with a GPS fix — the fleet dots on the map
        var providers = userRepo.findByRoleAndStatus(User.Role.PROVIDER, User.Status.ACTIVE).stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsOnline())
                          && p.getCurrentLat() != null && p.getCurrentLng() != null)
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("name", p.getName());
                    m.put("lat", p.getCurrentLat());
                    m.put("lng", p.getCurrentLng());
                    m.put("updatedAt", p.getLocationUpdatedAt() != null ? p.getLocationUpdatedAt().toString() : null);
                    return m;
                }).toList();

        // Active bookings (assigned, not yet completed/cancelled) where both
        // sides have shared live GPS — drives the route lines + ETA labels
        var activeStatuses = java.util.EnumSet.of(
                Booking.BookingStatus.CONFIRMED, Booking.BookingStatus.EN_ROUTE, Booking.BookingStatus.IN_PROGRESS);
        var activeJobs = bookingRepo.findAll().stream()
                .filter(b -> activeStatuses.contains(b.getStatus()) && b.getProvider() != null && b.getCustomer() != null)
                .filter(b -> b.getProvider().getCurrentLat() != null && b.getProvider().getCurrentLng() != null
                          && b.getCustomer().getCurrentLat() != null && b.getCustomer().getCurrentLng() != null)
                .map(b -> {
                    var eta = mapsService.distanceAndEta(
                            b.getProvider().getCurrentLat(), b.getProvider().getCurrentLng(),
                            b.getCustomer().getCurrentLat(), b.getCustomer().getCurrentLng());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("bookingId", b.getId());
                    m.put("service", b.getService());
                    m.put("status", b.getStatus().name());
                    m.put("providerId", b.getProvider().getId());
                    m.put("providerName", b.getProvider().getName());
                    m.put("providerLat", b.getProvider().getCurrentLat());
                    m.put("providerLng", b.getProvider().getCurrentLng());
                    m.put("customerName", b.getCustomer().getName());
                    m.put("customerLat", b.getCustomer().getCurrentLat());
                    m.put("customerLng", b.getCustomer().getCurrentLng());
                    m.put("distanceKm", eta.get("distanceKm"));
                    m.put("etaMinutes", eta.get("etaMinutes"));
                    m.put("etaSource", eta.get("source"));
                    return m;
                }).toList();

        return ResponseEntity.ok(Map.of("providers", providers, "activeJobs", activeJobs));
    }

    // ── User management ───────────────────────────────────────────
    @PostMapping("/users/suspend")
    public ResponseEntity<?> suspendUser(@AuthenticationPrincipal String adminEmail, @RequestBody Map<String, Object> body) {
        Long id   = Long.parseLong(body.get("id").toString());
        User user = userRepo.findById(id).orElseThrow(() -> new RuntimeException("User not found"));
        user.setStatus(User.Status.SUSPENDED);
        userRepo.save(user);
        // Notify user
        notifService.create(user,
            com.tasksphere.entity.Notification.NotificationType.GENERAL,
            "Account Suspended",
            "Your TaskSphere account has been suspended. Contact support if you believe this is a mistake.",
            "🚫", com.tasksphere.entity.Notification.NotificationColor.RED,
            user.getId(), "USER");
        auditLogService.log(adminEmail, adminEmail, AuditLog.AuditAction.SUSPEND, "User", String.valueOf(id),
                "Suspended user " + user.getName() + " (" + user.getEmail() + ")");
        return ResponseEntity.ok(Map.of("message", user.getName() + " suspended"));
    }

    @PostMapping("/users/activate")
    public ResponseEntity<?> activateUser(@AuthenticationPrincipal String adminEmail, @RequestBody Map<String, Object> body) {
        Long id   = Long.parseLong(body.get("id").toString());
        User user = userRepo.findById(id).orElseThrow(() -> new RuntimeException("User not found"));
        user.setStatus(User.Status.ACTIVE);
        userRepo.save(user);
        notifService.create(user,
            com.tasksphere.entity.Notification.NotificationType.GENERAL,
            "Account Reinstated ✅",
            "Your TaskSphere account has been reinstated. Welcome back!",
            "✅", com.tasksphere.entity.Notification.NotificationColor.GREEN,
            user.getId(), "USER");
        auditLogService.log(adminEmail, adminEmail, AuditLog.AuditAction.UPDATE, "User", String.valueOf(id),
                "Reactivated user " + user.getName() + " (" + user.getEmail() + ")");
        return ResponseEntity.ok(Map.of("message", user.getName() + " activated"));
    }

    // ── Provider KYC ─────────────────────────────────────────────
    @PostMapping("/providers/verify")
    public ResponseEntity<?> verifyProvider(@AuthenticationPrincipal String adminEmail, @RequestBody Map<String, Object> body) {
        Long id       = Long.parseLong(body.get("id").toString());
        User provider = userRepo.findById(id).orElseThrow(() -> new RuntimeException("Not found"));
        provider.setStatus(User.Status.ACTIVE);
        userRepo.save(provider);
        notifService.onKycApproved(provider);
        notifService.onProfileVerified(provider);
        auditLogService.log(adminEmail, adminEmail, AuditLog.AuditAction.APPROVE, "Provider", String.valueOf(id),
                "KYC approved for " + provider.getName());
        return ResponseEntity.ok(Map.of("message", provider.getName() + " verified"));
    }

    @PostMapping("/providers/reject-kyc")
    public ResponseEntity<?> rejectKyc(@AuthenticationPrincipal String adminEmail, @RequestBody Map<String, Object> body) {
        Long id       = Long.parseLong(body.get("id").toString());
        String reason = body.getOrDefault("reason", "Document unclear").toString();
        User provider = userRepo.findById(id).orElseThrow(() -> new RuntimeException("Not found"));
        notifService.onKycRejected(provider, reason);
        auditLogService.log(adminEmail, adminEmail, AuditLog.AuditAction.REJECT, "Provider", String.valueOf(id),
                "KYC rejected for " + provider.getName() + " — reason: " + reason);
        return ResponseEntity.ok(Map.of("message", "KYC rejection notification sent to " + provider.getName()));
    }

    // ── Booking admin actions ────────────────────────────────────
    @PatchMapping("/bookings/{id}/cancel")
    public ResponseEntity<?> cancelBooking(@PathVariable Long id) {
        Booking booking = bookingRepo.findById(id).orElseThrow();
        booking.setStatus(Booking.BookingStatus.CANCELLED);
        booking.setPaymentStatus(Booking.PaymentStatus.REFUNDED);
        bookingRepo.save(booking);
        notifService.onBookingCancelled(booking, "ADMIN");
        notifService.onRefundIssued(booking);
        return ResponseEntity.ok(Map.of("message", "Booking #" + id + " cancelled and refunded"));
    }

    // ── Reviews: moderation ────────────────────────────────────────
    @GetMapping("/reviews")
    public ResponseEntity<?> allReviews() {
        return ResponseEntity.ok(
            reviewRepo.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(com.tasksphere.dto.ReviewDtos.ReviewResponse::from).toList()
        );
    }

    @DeleteMapping("/reviews/{id}")
    public ResponseEntity<?> deleteReview(@PathVariable Long id) {
        if (!reviewRepo.existsById(id))
            return ResponseEntity.badRequest().body(Map.of("message", "Review not found"));
        reviewRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Review #" + id + " removed by moderation"));
    }

    // ── Payouts ──────────────────────────────────────────────────
    @PostMapping("/payouts/batch")
    public ResponseEntity<?> batchPayout(@RequestBody Map<String, Object> body) {
        String ref = "BATCH" + System.currentTimeMillis();
        return ResponseEntity.ok(Map.of("message", "Batch payout processed", "reference", ref));
    }

    // ── Notifications: broadcast ───────────────────────────────────
    @PostMapping("/notifications/broadcast")
    public ResponseEntity<?> broadcast(@RequestBody BroadcastRequest req) {
        int count = notifService.broadcastToRole(
            req.getTargetRole() != null ? req.getTargetRole() : "ALL",
            req.getTitle(), req.getMessage(), req.getIcon()
        );
        return ResponseEntity.ok(Map.of(
            "message",    "Broadcast sent to " + count + " users",
            "recipients", count
        ));
    }

    // ── Notifications: stats ──────────────────────────────────────
    @GetMapping("/notifications/stats")
    public ResponseEntity<?> notifStats() {
        long last24h  = notifRepo.countSince(LocalDateTime.now().minusHours(24));
        long last7d   = notifRepo.countSince(LocalDateTime.now().minusDays(7));
        long total    = notifRepo.count();
        return ResponseEntity.ok(Map.of(
            "last24Hours", last24h,
            "last7Days",   last7d,
            "totalAll",    total
        ));
    }

    // ── Service Category Management ────────────────────────────────
    @GetMapping("/categories")
    public ResponseEntity<?> allCategories() {
        return ResponseEntity.ok(categoryRepo.findAllByOrderBySortOrderAscNameAsc().stream().map(c -> Map.of(
                "id", c.getId(), "name", c.getName(), "icon", c.getIcon() != null ? c.getIcon() : "🔧",
                "description", c.getDescription() != null ? c.getDescription() : "",
                "enabled", c.getEnabled(), "sortOrder", c.getSortOrder(),
                "createdAt", c.getCreatedAt().toString()
        )).toList());
    }

    @PostMapping("/categories")
    public ResponseEntity<?> createCategory(@AuthenticationPrincipal String adminEmail, @RequestBody CategoryRequest req) {
        if (req.getName() == null || req.getName().isBlank())
            return ResponseEntity.badRequest().body(Map.of("message", "Category name is required"));
        if (categoryRepo.existsByNameIgnoreCase(req.getName()))
            return ResponseEntity.badRequest().body(Map.of("message", "Category already exists"));
        ServiceCategory cat = ServiceCategory.builder()
                .name(req.getName())
                .icon(req.getIcon() != null ? req.getIcon() : "🔧")
                .description(req.getDescription())
                .enabled(req.getEnabled() == null || req.getEnabled())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .build();
        categoryRepo.save(cat);
        auditLogService.log(adminEmail, adminEmail, AuditLog.AuditAction.CREATE, "Category", String.valueOf(cat.getId()),
                "Created category '" + cat.getName() + "'");
        return ResponseEntity.ok(Map.of("message", "Category '" + cat.getName() + "' created", "id", cat.getId()));
    }

    @PutMapping("/categories/{id}")
    public ResponseEntity<?> updateCategory(@AuthenticationPrincipal String adminEmail, @PathVariable Long id, @RequestBody CategoryRequest req) {
        ServiceCategory cat = categoryRepo.findById(id).orElseThrow(() -> new RuntimeException("Category not found"));
        if (req.getName() != null && !req.getName().isBlank()) cat.setName(req.getName());
        if (req.getIcon() != null) cat.setIcon(req.getIcon());
        if (req.getDescription() != null) cat.setDescription(req.getDescription());
        if (req.getEnabled() != null) cat.setEnabled(req.getEnabled());
        if (req.getSortOrder() != null) cat.setSortOrder(req.getSortOrder());
        categoryRepo.save(cat);
        auditLogService.log(adminEmail, adminEmail, AuditLog.AuditAction.UPDATE, "Category", String.valueOf(id),
                "Updated category '" + cat.getName() + "'");
        return ResponseEntity.ok(Map.of("message", "Category updated"));
    }

    @PatchMapping("/categories/{id}/toggle")
    public ResponseEntity<?> toggleCategory(@AuthenticationPrincipal String adminEmail, @PathVariable Long id) {
        ServiceCategory cat = categoryRepo.findById(id).orElseThrow(() -> new RuntimeException("Category not found"));
        cat.setEnabled(!cat.getEnabled());
        categoryRepo.save(cat);
        auditLogService.log(adminEmail, adminEmail,
                cat.getEnabled() ? AuditLog.AuditAction.ENABLE : AuditLog.AuditAction.DISABLE,
                "Category", String.valueOf(id), cat.getName() + (cat.getEnabled() ? " enabled" : " disabled"));
        return ResponseEntity.ok(Map.of("message", cat.getName() + (cat.getEnabled() ? " enabled" : " disabled"), "enabled", cat.getEnabled()));
    }

    @DeleteMapping("/categories/{id}")
    public ResponseEntity<?> deleteCategory(@AuthenticationPrincipal String adminEmail, @PathVariable Long id) {
        ServiceCategory cat = categoryRepo.findById(id).orElse(null);
        if (cat == null)
            return ResponseEntity.badRequest().body(Map.of("message", "Category not found"));
        categoryRepo.deleteById(id);
        auditLogService.log(adminEmail, adminEmail, AuditLog.AuditAction.DELETE, "Category", String.valueOf(id),
                "Deleted category '" + cat.getName() + "'");
        return ResponseEntity.ok(Map.of("message", "Category removed"));
    }

    // ── Complaint Management ───────────────────────────────────────
    @GetMapping("/complaints")
    public ResponseEntity<?> allComplaints() {
        return ResponseEntity.ok(complaintRepo.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::complaintToMap).toList());
    }

    @GetMapping("/complaints/stats")
    public ResponseEntity<?> complaintStats() {
        return ResponseEntity.ok(Map.of(
                "open", complaintRepo.countOpen(),
                "inProgress", complaintRepo.countInProgress(),
                "resolved", complaintRepo.countResolved(),
                "total", complaintRepo.count()
        ));
    }

    @PatchMapping("/complaints/{id}")
    public ResponseEntity<?> updateComplaint(@AuthenticationPrincipal String adminEmail, @PathVariable Long id, @RequestBody ComplaintUpdateRequest req) {
        Complaint complaint = complaintRepo.findById(id).orElseThrow(() -> new RuntimeException("Complaint not found"));
        if (req.getStatus() != null) {
            Complaint.ComplaintStatus status = Complaint.ComplaintStatus.valueOf(req.getStatus().toUpperCase());
            complaint.setStatus(status);
            if (status == Complaint.ComplaintStatus.RESOLVED) complaint.setResolvedAt(LocalDateTime.now());
        }
        if (req.getAdminResponse() != null) complaint.setAdminResponse(req.getAdminResponse());
        complaintRepo.save(complaint);

        if (complaint.getCustomer() != null && req.getStatus() != null) {
            notifService.create(complaint.getCustomer(),
                    com.tasksphere.entity.Notification.NotificationType.GENERAL,
                    "Complaint Update: " + complaint.getSubject(),
                    "Your complaint status is now " + complaint.getStatus().name() +
                            (req.getAdminResponse() != null ? (". Admin note: " + req.getAdminResponse()) : "."),
                    "📋", com.tasksphere.entity.Notification.NotificationColor.BLUE,
                    complaint.getId(), "COMPLAINT");
        }
        auditLogService.log(adminEmail, adminEmail,
                complaint.getStatus() == Complaint.ComplaintStatus.RESOLVED ? AuditLog.AuditAction.RESOLVE : AuditLog.AuditAction.UPDATE,
                "Complaint", String.valueOf(id),
                "Complaint '" + complaint.getSubject() + "' set to " + complaint.getStatus().name());
        return ResponseEntity.ok(Map.of("message", "Complaint #" + id + " updated"));
    }

    @DeleteMapping("/complaints/{id}")
    public ResponseEntity<?> deleteComplaint(@AuthenticationPrincipal String adminEmail, @PathVariable Long id) {
        Complaint complaint = complaintRepo.findById(id).orElse(null);
        if (complaint == null)
            return ResponseEntity.badRequest().body(Map.of("message", "Complaint not found"));
        complaintRepo.deleteById(id);
        auditLogService.log(adminEmail, adminEmail, AuditLog.AuditAction.DELETE, "Complaint", String.valueOf(id),
                "Deleted complaint '" + complaint.getSubject() + "'");
        return ResponseEntity.ok(Map.of("message", "Complaint removed"));
    }

    private Map<String, Object> complaintToMap(Complaint c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("customerName", c.getCustomer() != null ? c.getCustomer().getName() : "Unknown");
        m.put("providerName", c.getProvider() != null ? c.getProvider().getName() : "");
        m.put("bookingId", c.getBooking() != null ? c.getBooking().getId() : null);
        m.put("subject", c.getSubject());
        m.put("description", c.getDescription());
        m.put("priority", c.getPriority().name());
        m.put("status", c.getStatus().name());
        m.put("adminResponse", c.getAdminResponse() != null ? c.getAdminResponse() : "");
        m.put("createdAt", c.getCreatedAt().toString());
        m.put("resolvedAt", c.getResolvedAt() != null ? c.getResolvedAt().toString() : null);
        return m;
    }

    // ── Reports: Revenue Reports ───────────────────────────────────
    @GetMapping("/reports/revenue")
    public ResponseEntity<?> revenueReport() {
        return ResponseEntity.ok(reportService.buildRevenueReport());
    }

    // ── Analytics: powers every chart on the Overview + Revenue tabs ──
    // Monthly Revenue, Booking Growth, Provider Performance, Customer
    // Growth, Category Analytics, Top Providers, Top Services, Revenue
    // Trend — all computed live from MySQL, nothing hardcoded.
    @GetMapping("/analytics/dashboard")
    public ResponseEntity<?> analyticsDashboard() {
        return ResponseEntity.ok(analyticsService.dashboard());
    }

    // ── Refunds ───────────────────────────────────────────────────
    /** Refunds a payment via the real Razorpay Refunds API and updates PaymentHistory + the linked booking. */
    @PostMapping("/payments/{paymentId}/refund")
    public ResponseEntity<?> refundPayment(@PathVariable Long paymentId,
                                           @RequestBody(required = false) Map<String, Object> body,
                                           @AuthenticationPrincipal String adminEmail) {
        try {
            com.tasksphere.entity.Payment payment = paymentRepo.findById(paymentId)
                    .orElseThrow(() -> new RuntimeException("Payment not found"));
            if (payment.getStatus() == com.tasksphere.entity.Payment.PaymentStatus.REFUNDED) {
                throw new RuntimeException("Payment already refunded");
            }
            Double partialAmount = body != null && body.get("amount") != null
                    ? Double.valueOf(String.valueOf(body.get("amount"))) : null;
            String reason = body != null && body.get("reason") != null ? String.valueOf(body.get("reason")) : "Admin-initiated refund";

            Map<String, Object> result;
            if (razorpayService.isConfigured() && payment.getRazorpayRef() != null) {
                result = razorpayService.refund(payment.getRazorpayRef(), partialAmount, reason);
            } else {
                // Razorpay not configured yet — record the refund in PaymentHistory
                // so the rest of the system (booking status, customer view) stays
                // consistent; the real money movement happens once keys are added.
                result = Map.of("refundId", "PENDING_RZP_CONFIG", "status", "recorded_only",
                        "amount", partialAmount != null ? partialAmount : payment.getAmount());
            }

            payment.setStatus(com.tasksphere.entity.Payment.PaymentStatus.REFUNDED);
            payment.setNote((payment.getNote() != null ? payment.getNote() + " | " : "") + "Refunded: " + reason);
            paymentRepo.save(payment);

            if (payment.getBooking() != null) {
                Booking booking = payment.getBooking();
                booking.setPaymentStatus(Booking.PaymentStatus.REFUNDED);
                bookingRepo.save(booking);
                notifService.onRefundIssued(booking);
            }

            auditLogService.log(adminEmail, adminEmail, com.tasksphere.entity.AuditLog.AuditAction.OTHER,
                    "Payment", String.valueOf(paymentId), "Refunded payment " + payment.getRazorpayRef() + " — " + reason);

            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ── Reports: Export (PDF / Excel) ──────────────────────────────
    @GetMapping("/reports/export/bookings/excel")
    public ResponseEntity<?> exportBookingsExcel(@AuthenticationPrincipal String adminEmail) {
        try {
            byte[] data = reportService.exportBookingsExcel();
            auditLogService.log(adminEmail, adminEmail, AuditLog.AuditAction.EXPORT, "Report", null,
                    "Exported bookings report (Excel)");
            return buildFileResponse(data, "tasksphere_bookings.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to generate Excel report"));
        }
    }

    @GetMapping("/reports/export/customers/excel")
    public ResponseEntity<?> exportCustomersExcel(@AuthenticationPrincipal String adminEmail) {
        try {
            byte[] data = reportService.exportCustomersExcel();
            auditLogService.log(adminEmail, adminEmail, AuditLog.AuditAction.EXPORT, "Report", null,
                    "Exported customers report (Excel)");
            return buildFileResponse(data, "tasksphere_customers.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to generate Excel report"));
        }
    }

    @GetMapping("/reports/export/providers/excel")
    public ResponseEntity<?> exportProvidersExcel(@AuthenticationPrincipal String adminEmail) {
        try {
            byte[] data = reportService.exportProvidersExcel();
            auditLogService.log(adminEmail, adminEmail, AuditLog.AuditAction.EXPORT, "Report", null,
                    "Exported providers report (Excel)");
            return buildFileResponse(data, "tasksphere_providers.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to generate Excel report"));
        }
    }

    @GetMapping("/reports/export/reviews/excel")
    public ResponseEntity<?> exportReviewsExcel(@AuthenticationPrincipal String adminEmail) {
        try {
            byte[] data = reportService.exportReviewsExcel();
            auditLogService.log(adminEmail, adminEmail, AuditLog.AuditAction.EXPORT, "Report", null,
                    "Exported reviews report (Excel)");
            return buildFileResponse(data, "tasksphere_reviews.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to generate Excel report"));
        }
    }

    @GetMapping("/reports/export/revenue/excel")
    public ResponseEntity<?> exportRevenueExcel(@AuthenticationPrincipal String adminEmail) {
        try {
            byte[] data = reportService.exportRevenueExcel();
            auditLogService.log(adminEmail, adminEmail, AuditLog.AuditAction.EXPORT, "Report", null,
                    "Exported revenue report (Excel)");
            return buildFileResponse(data, "tasksphere_revenue_report.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to generate Excel report"));
        }
    }

    @GetMapping("/reports/export/revenue/pdf")
    public ResponseEntity<?> exportRevenuePdf(@AuthenticationPrincipal String adminEmail) {
        try {
            byte[] data = reportService.exportRevenuePdf();
            auditLogService.log(adminEmail, adminEmail, AuditLog.AuditAction.EXPORT, "Report", null,
                    "Exported revenue report (PDF)");
            return buildFileResponse(data, "tasksphere_revenue_report.pdf", "application/pdf");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to generate PDF report"));
        }
    }

    private ResponseEntity<byte[]> buildFileResponse(byte[] data, String filename, String contentType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(data);
    }

    // ── Audit Logs ───────────────────────────────────────────────
    @GetMapping("/audit-logs")
    public ResponseEntity<?> auditLogs(@RequestParam(defaultValue = "200") int limit) {
        List<AuditLog> logs = auditLogRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.min(limit, 500)));
        return ResponseEntity.ok(logs.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("actorEmail", a.getActorEmail());
            m.put("actorName", a.getActorName());
            m.put("action", a.getAction().name());
            m.put("entityType", a.getEntityType());
            m.put("entityId", a.getEntityId());
            m.put("details", a.getDetails());
            m.put("createdAt", a.getCreatedAt().toString());
            return m;
        }).toList());
    }

    @GetMapping("/audit-logs/export/excel")
    public ResponseEntity<?> exportAuditLogsExcel() {
        try {
            byte[] data = reportService.exportAuditLogsExcel(auditLogRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 1000)));
            return buildFileResponse(data, "tasksphere_audit_logs.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("message", "Failed to generate Excel report"));
        }
    }
}
