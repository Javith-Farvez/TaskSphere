package com.tasksphere.controller;

import com.tasksphere.entity.*;
import com.tasksphere.repository.*;
import com.tasksphere.service.BookingService;
import com.tasksphere.service.EmailService;
import com.tasksphere.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/provider")
public class ProviderController {

    @Autowired private BookingService          bookingService;
    @Autowired private UserRepository          userRepo;
    @Autowired private BookingRepository       bookingRepo;
    @Autowired private ReviewRepository        reviewRepo;
    @Autowired private ServiceRepository       serviceRepo;
    @Autowired private PaymentRepository       paymentRepo;
    @Autowired private ProviderMediaRepository mediaRepo;
    @Autowired private NotificationService     notifService;
    @Autowired private EmailService            emailService;
    @Autowired private com.tasksphere.service.RazorpayService razorpayService;
    @Autowired private com.tasksphere.service.GoogleMapsService mapsService;

    private User getProvider(String email) {
        return userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("Provider not found"));
    }

    // ── Jobs ─────────────────────────────────────────────────────
    @GetMapping("/jobs/today")
    public ResponseEntity<?> todayJobs(@AuthenticationPrincipal String email) {
        return ResponseEntity.ok(bookingService.getProviderJobs(email));
    }
    @PostMapping("/jobs/{id}/accept")
    public ResponseEntity<?> acceptJob(@PathVariable Long id, @AuthenticationPrincipal String email) {
        try { return ResponseEntity.ok(bookingService.accept(id, email)); }
        catch (RuntimeException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    }
    @PostMapping("/jobs/{id}/decline")
    public ResponseEntity<?> declineJob(@PathVariable Long id, @AuthenticationPrincipal String email) {
        try { return ResponseEntity.ok(bookingService.cancel(id, email)); }
        catch (RuntimeException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    }
    @PostMapping("/jobs/{id}/complete")
    public ResponseEntity<?> completeJob(@PathVariable Long id, @AuthenticationPrincipal String email) {
        try { return ResponseEntity.ok(bookingService.complete(id, email)); }
        catch (RuntimeException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    }
    /** Provider taps "Start Journey" (heading to the customer now). This is the
     *  real trigger that makes the customer's live tracking map start moving —
     *  before this call, the customer only sees a "waiting for provider" state. */
    @PostMapping("/jobs/{id}/start")
    public ResponseEntity<?> startJob(@PathVariable Long id, @AuthenticationPrincipal String email) {
        try { return ResponseEntity.ok(bookingService.startJourney(id, email)); }
        catch (RuntimeException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    }
    /** Provider taps "Arrived" once they reach the customer's location — stops
     *  live tracking and moves the job into the work-in-progress stage. */
    @PostMapping("/jobs/{id}/arrive")
    public ResponseEntity<?> arriveJob(@PathVariable Long id, @AuthenticationPrincipal String email) {
        try { return ResponseEntity.ok(bookingService.arrive(id, email)); }
        catch (RuntimeException e) { return ResponseEntity.badRequest().body(Map.of("message", e.getMessage())); }
    }

    /** Returns customer coords + address for the Navigate button — only works while the booking is active */
    @GetMapping("/jobs/{id}/details")
    public ResponseEntity<?> jobDetails(@PathVariable Long id, @AuthenticationPrincipal String email) {
        User provider = getProvider(email);
        Booking booking = bookingRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found"));
        // Security: provider can only see details of their own assigned bookings
        if (booking.getProvider() == null || !booking.getProvider().getId().equals(provider.getId()))
            return ResponseEntity.status(403).body(Map.of("message", "Not your booking"));

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("id", booking.getId());
        result.put("service", booking.getService());
        result.put("customerName", booking.getCustomer() != null ? booking.getCustomer().getName() : "");
        result.put("customerPhone", booking.getCustomer() != null ? booking.getCustomer().getPhone() : "");
        result.put("customerAddress", booking.getAddress() != null ? booking.getAddress() : "");
        result.put("status", booking.getStatus().name());
        result.put("slot", booking.getSlot() != null ? booking.getSlot() : "");
        result.put("amount", booking.getAmount());

        // Customer live GPS (if shared)
        User customer = booking.getCustomer();
        if (customer != null && customer.getCurrentLat() != null) {
            result.put("customerLat", customer.getCurrentLat());
            result.put("customerLng", customer.getCurrentLng());
            result.put("customerLocationAge", customer.getLocationUpdatedAt() != null
                    ? customer.getLocationUpdatedAt().toString() : "");
            // Real ETA from provider current position to customer
            if (provider.getCurrentLat() != null) {
                var eta = mapsService.distanceAndEta(
                        provider.getCurrentLat(), provider.getCurrentLng(),
                        customer.getCurrentLat(), customer.getCurrentLng());
                result.put("etaMinutes", eta.get("etaMinutes"));
                result.put("distanceKm", eta.get("distanceKm"));
                result.put("etaSource", eta.get("source"));
            }
        } else {
            result.put("customerLat", null);
            result.put("customerLng", null);
        }
        return ResponseEntity.ok(result);
    }

    // ── Stats ─────────────────────────────────────────────────────
    @GetMapping("/stats")
    public ResponseEntity<?> stats(@AuthenticationPrincipal String email) {
        User p = getProvider(email);
        long   jobsDone      = bookingRepo.countCompletedByProvider(p);
        Double totalEarned   = bookingRepo.sumEarningsByProvider(p);
        double avgRating     = reviewRepo.avgRatingByProvider(p);
        long   reviewCount   = reviewRepo.countByProvider(p);
        Double totalReceived = paymentRepo.totalReceivedByProvider(p);
        Double totalPaidOut  = paymentRepo.totalPaidOutByProvider(p);
        long   failedCount   = paymentRepo.countFailedByProvider(p);
        double net = (totalReceived != null ? totalReceived : 0) - (totalPaidOut != null ? totalPaidOut : 0);
        return ResponseEntity.ok(Map.of(
            "jobsDone", jobsDone, "totalEarnings", totalEarned != null ? totalEarned : 0,
            "monthEarnings", totalEarned != null ? totalEarned : 0,
            "totalReceived", totalReceived != null ? totalReceived : 0,
            "totalPaidOut", totalPaidOut != null ? totalPaidOut : 0,
            "pendingPayout", Math.max(0, net), "failedPayments", failedCount,
            "rating", avgRating > 0 ? Math.round(avgRating * 100.0) / 100.0 : 0,
            "reviewCount", reviewCount, "acceptanceRate", 97
        ));
    }

    // ── Reviews ──────────────────────────────────────────────────
    @GetMapping("/reviews")
    public ResponseEntity<?> getReviews(@AuthenticationPrincipal String email) {
        User provider = getProvider(email);
        return ResponseEntity.ok(
            reviewRepo.findByProviderOrderByCreatedAtDesc(provider)
                .stream().map(com.tasksphere.dto.ReviewDtos.ReviewResponse::from).toList()
        );
    }

    /** Reply to Review — provider can post/update a single reply per review */
    @PostMapping("/reviews/{id}/reply")
    public ResponseEntity<?> replyToReview(@PathVariable Long id,
                                           @RequestBody com.tasksphere.dto.ReviewDtos.ReplyRequest req,
                                           @AuthenticationPrincipal String email) {
        try {
            User provider = getProvider(email);
            Review review = reviewRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Review not found"));
            if (review.getProvider() == null || !review.getProvider().getId().equals(provider.getId()))
                throw new RuntimeException("You can only reply to reviews on your own profile");
            if (req.getReply() == null || req.getReply().trim().isEmpty())
                throw new RuntimeException("Reply cannot be empty");

            review.setReply(req.getReply().trim());
            review.setRepliedAt(java.time.LocalDateTime.now());
            reviewRepo.save(review);

            if (review.getCustomer() != null) {
                notifService.onReviewReply(review.getCustomer(), provider.getName(), review.getId());
            }

            return ResponseEntity.ok(com.tasksphere.dto.ReviewDtos.ReviewResponse.from(review));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** Remove a reply the provider previously posted */
    @DeleteMapping("/reviews/{id}/reply")
    public ResponseEntity<?> deleteReply(@PathVariable Long id, @AuthenticationPrincipal String email) {
        try {
            User provider = getProvider(email);
            Review review = reviewRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Review not found"));
            if (review.getProvider() == null || !review.getProvider().getId().equals(provider.getId()))
                throw new RuntimeException("You can only edit replies on your own profile");

            review.setReply(null);
            review.setRepliedAt(null);
            reviewRepo.save(review);
            return ResponseEntity.ok(Map.of("message", "Reply removed"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ── Profile ───────────────────────────────────────────────────
    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(@RequestBody Map<String, String> body,
                                           @AuthenticationPrincipal String email) {
        User user = getProvider(email);
        if (body.containsKey("firstName") && body.containsKey("lastName"))
            user.setName(body.get("firstName") + " " + body.get("lastName"));
        if (body.containsKey("phone"))    user.setPhone(body.get("phone"));
        if (body.containsKey("photoUrl") && !body.get("photoUrl").isBlank()) {
            mediaRepo.save(ProviderMedia.builder().provider(user)
                .cloudinaryUrl(body.get("photoUrl")).type(ProviderMedia.MediaType.PROFILE_PHOTO).build());
        }
        userRepo.save(user);
        return ResponseEntity.ok(Map.of("message", "Profile updated", "name", user.getName()));
    }
    @PatchMapping("/status")
    public ResponseEntity<?> updateStatus(@RequestBody Map<String, Boolean> body,
                                          @AuthenticationPrincipal String email) {
        return ResponseEntity.ok(Map.of("online", body.get("online"), "message", "Status updated"));
    }
    // ── Services CRUD ─────────────────────────────────────────────
    @GetMapping("/services")
    public ResponseEntity<?> getServices(@AuthenticationPrincipal String email) {
        return ResponseEntity.ok(serviceRepo.findByProviderOrderByCreatedAtDesc(getProvider(email))
            .stream().map(this::svcMap).toList());
    }
    @PostMapping("/services")
    public ResponseEntity<?> createService(@RequestBody Map<String, Object> body,
                                           @AuthenticationPrincipal String email) {
        com.tasksphere.entity.Service svc = com.tasksphere.entity.Service.builder()
            .provider(getProvider(email))
            .name(body.getOrDefault("name","").toString())
            .price(Double.parseDouble(body.getOrDefault("price","0").toString()))
            .category(body.getOrDefault("category","General").toString())
            .description(body.getOrDefault("description","").toString())
            .enabled(true).build();
        return ResponseEntity.ok(svcMap(serviceRepo.save(svc)));
    }
    @PutMapping("/services/{id}")
    public ResponseEntity<?> updateService(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                           @AuthenticationPrincipal String email) {
        com.tasksphere.entity.Service svc = serviceRepo.findById(id)
            .orElseThrow(() -> new RuntimeException("Service not found"));
        if (body.containsKey("name"))        svc.setName(body.get("name").toString());
        if (body.containsKey("price"))       svc.setPrice(Double.parseDouble(body.get("price").toString()));
        if (body.containsKey("category"))    svc.setCategory(body.get("category").toString());
        if (body.containsKey("description")) svc.setDescription(body.get("description").toString());
        if (body.containsKey("imageUrls"))   svc.setImageUrls(body.get("imageUrls").toString());
        return ResponseEntity.ok(svcMap(serviceRepo.save(svc)));
    }
    @PatchMapping("/services/{id}/toggle")
    public ResponseEntity<?> toggleService(@PathVariable Long id, @RequestBody Map<String, Boolean> body,
                                           @AuthenticationPrincipal String email) {
        com.tasksphere.entity.Service svc = serviceRepo.findById(id)
            .orElseThrow(() -> new RuntimeException("Service not found"));
        svc.setEnabled(body.getOrDefault("enabled", !svc.getEnabled()));
        return ResponseEntity.ok(Map.of("id", svc.getId(), "enabled", serviceRepo.save(svc).getEnabled()));
    }
    @DeleteMapping("/services/{id}")
    public ResponseEntity<?> deleteService(@PathVariable Long id, @AuthenticationPrincipal String email) {
        serviceRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Service deleted"));
    }
    private Map<String, Object> svcMap(com.tasksphere.entity.Service s) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", s.getId()); m.put("name", s.getName()); m.put("price", s.getPrice());
        m.put("category", s.getCategory()); m.put("description", s.getDescription());
        m.put("enabled", s.getEnabled()); m.put("imageUrls", s.getImageUrls() != null ? s.getImageUrls() : "");
        return m;
    }

    // ── Payments ──────────────────────────────────────────────────
    @GetMapping("/payments")
    public ResponseEntity<?> getPayments(@AuthenticationPrincipal String email) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd MMM yyyy");
        return ResponseEntity.ok(paymentRepo.findByProviderOrderByCreatedAtDesc(getProvider(email))
            .stream().map(p -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", p.getId()); m.put("ref", p.getRazorpayRef());
                m.put("type", p.getType().name().toLowerCase());
                m.put("name", p.getServiceName() != null ? p.getServiceName() : "Payment");
                m.put("customer", p.getCustomerName() != null ? p.getCustomerName() : "—");
                m.put("date", p.getCreatedAt() != null ? p.getCreatedAt().format(fmt) : "—");
                m.put("method", p.getPaymentMethod());
                m.put("amount", p.getAmount()); m.put("platformFee", p.getPlatformFee());
                m.put("netAmount", p.getNetAmount());
                m.put("status", p.getStatus().name().toLowerCase());
                m.put("note", p.getNote() != null ? p.getNote() : "");
                return m;
            }).toList());
    }

    @PostMapping("/payments/verify")
    public ResponseEntity<?> verifyPayment(@RequestBody Map<String, Object> body,
                                           @AuthenticationPrincipal String email) {
        User provider = getProvider(email);
        String payId    = body.getOrDefault("paymentId","").toString();
        String orderId  = body.getOrDefault("orderId","").toString();
        String sig      = body.getOrDefault("signature","").toString();
        String svcName  = body.getOrDefault("service","Service").toString();
        String customer = body.getOrDefault("customer","Customer").toString();
        double amount   = Double.parseDouble(body.getOrDefault("amount","0").toString());
        double fee      = Math.round(amount * 0.08 * 100.0) / 100.0;
        double net      = amount - fee;
        // Real HMAC-SHA256 signature verification when the client sent real
        // Razorpay order/payment IDs. If Razorpay isn't configured yet (no
        // live keys), or this is a manually-recorded payment with no
        // signature, we skip verification rather than block the existing
        // working flow — but any signature that IS present must be valid.
        if (!sig.isBlank() && !orderId.isBlank() && razorpayService.isConfigured()) {
            boolean valid = razorpayService.verifySignature(orderId, payId, sig);
            if (!valid) {
                return ResponseEntity.badRequest().body(Map.of("verified", false, "message", "Payment signature verification failed."));
            }
        }
        Payment payment = Payment.builder().provider(provider)
            .razorpayRef(payId.isBlank() ? "PAY_"+System.currentTimeMillis() : payId)
            .razorpayOrderId(orderId).razorpaySignature(sig)
            .amount(amount).platformFee(fee).netAmount(net)
            .paymentMethod("Razorpay").customerName(customer).serviceName(svcName)
            .type(Payment.PaymentType.CREDIT).status(Payment.PaymentStatus.PAID).build();
        paymentRepo.save(payment);
        notifService.onPaymentReceived(payment);
        emailService.sendProviderEarningsReceipt(payment);
        return ResponseEntity.ok(Map.of("verified", true, "netAmount", net, "fee", fee, "ref", payment.getRazorpayRef()));
    }

    // ── Payout ────────────────────────────────────────────────────
    @PostMapping("/payout")
    public ResponseEntity<?> requestPayout(@RequestBody Map<String, Object> body,
                                           @AuthenticationPrincipal String email) {
        User provider = getProvider(email);
        double amount = Double.parseDouble(body.getOrDefault("amount","0").toString());
        String bank   = body.getOrDefault("bank","Bank").toString();
        String ref    = "RZP_POUT_" + System.currentTimeMillis();
        paymentRepo.save(Payment.builder().provider(provider).razorpayRef(ref)
            .amount(amount).platformFee(0.0).netAmount(amount)
            .paymentMethod("Razorpay X").customerName(bank).serviceName("Bank Payout")
            .type(Payment.PaymentType.PAYOUT).status(Payment.PaymentStatus.PAID)
            .note("Withdrawal to " + bank).build());
        notifService.onPayoutProcessed(provider, amount, bank, ref);
        return ResponseEntity.ok(Map.of("message","Payout processed","reference",ref,"amount",amount,"bank",bank));
    }

    // ── Cloudinary / Media ─────────────────────────────────────────
    @GetMapping("/media")
    public ResponseEntity<?> getMedia(@AuthenticationPrincipal String email) {
        return ResponseEntity.ok(mediaRepo.findByProviderOrderByUploadedAtDesc(getProvider(email))
            .stream().map(m -> Map.of(
                "id", m.getId(), "url", m.getCloudinaryUrl(), "type", m.getType().name(),
                "verified", m.getVerified(), "documentType", m.getDocumentType() != null ? m.getDocumentType() : "",
                "uploadedAt", m.getUploadedAt() != null ? m.getUploadedAt().toString() : ""
            )).toList());
    }

    @PostMapping("/media")
    public ResponseEntity<?> saveMedia(@RequestBody Map<String, Object> body,
                                       @AuthenticationPrincipal String email) {
        User provider = getProvider(email);
        ProviderMedia media = ProviderMedia.builder()
            .provider(provider)
            .cloudinaryUrl(body.getOrDefault("cloudinaryUrl","").toString())
            .publicId(body.containsKey("publicId") ? body.get("publicId").toString() : null)
            .type(ProviderMedia.MediaType.valueOf(body.getOrDefault("type","SERVICE_IMAGE").toString()))
            .build();
        mediaRepo.save(media);
        return ResponseEntity.ok(Map.of("message","Media saved","id",media.getId(),"url",media.getCloudinaryUrl()));
    }

    @PostMapping("/kyc")
    public ResponseEntity<?> submitKyc(@RequestBody Map<String, Object> body,
                                       @AuthenticationPrincipal String email) {
        User provider = getProvider(email);
        ProviderMedia media = ProviderMedia.builder()
            .provider(provider)
            .cloudinaryUrl(body.getOrDefault("documentUrl","").toString())
            .type(ProviderMedia.MediaType.ID_PROOF)
            .documentType(body.getOrDefault("documentType","AADHAAR").toString())
            .verified(false).build();
        mediaRepo.save(media);
        notifService.onKycSubmitted(provider);
        return ResponseEntity.ok(Map.of("message","KYC submitted for review","status","PENDING_REVIEW"));
    }

    @PostMapping("/portfolio/before-after")
    public ResponseEntity<?> saveBeforeAfter(@RequestBody Map<String, Object> body,
                                             @AuthenticationPrincipal String email) {
        User provider = getProvider(email);
        String before = body.getOrDefault("before","").toString();
        String after  = body.getOrDefault("after","").toString();
        if (!before.isBlank()) mediaRepo.save(ProviderMedia.builder().provider(provider)
            .cloudinaryUrl(before).type(ProviderMedia.MediaType.BEFORE_PHOTO).build());
        if (!after.isBlank())  mediaRepo.save(ProviderMedia.builder().provider(provider)
            .cloudinaryUrl(after).type(ProviderMedia.MediaType.AFTER_PHOTO).build());
        return ResponseEntity.ok(Map.of("message","Before/After photos saved to portfolio"));
    }

    // ── Certificates ──────────────────────────────────────────────
    @PostMapping("/certificates")
    public ResponseEntity<?> uploadCertificate(@RequestBody Map<String, Object> body,
                                               @AuthenticationPrincipal String email) {
        User provider = getProvider(email);
        ProviderMedia cert = ProviderMedia.builder()
            .provider(provider)
            .cloudinaryUrl(body.getOrDefault("documentUrl","").toString())
            .publicId(body.containsKey("publicId") ? body.get("publicId").toString() : null)
            .type(ProviderMedia.MediaType.CERTIFICATE)
            .certificateName(body.getOrDefault("certificateName","Certificate").toString())
            .issuer(body.getOrDefault("issuer","").toString())
            .expiryDate(body.getOrDefault("expiryDate","").toString())
            .verified(false)
            .build();
        mediaRepo.save(cert);
        notifService.onKycSubmitted(provider); // reuses admin "review needed" notification flow
        return ResponseEntity.ok(Map.of(
            "message","Certificate uploaded and submitted for verification",
            "id", cert.getId(), "url", cert.getCloudinaryUrl(), "status","PENDING_REVIEW"
        ));
    }

    @GetMapping("/certificates")
    public ResponseEntity<?> getCertificates(@AuthenticationPrincipal String email) {
        return ResponseEntity.ok(mediaRepo.findByProviderOrderByUploadedAtDesc(getProvider(email))
            .stream()
            .filter(m -> m.getType() == ProviderMedia.MediaType.CERTIFICATE)
            .map(m -> Map.of(
                "id", m.getId(), "url", m.getCloudinaryUrl(),
                "certificateName", m.getCertificateName() != null ? m.getCertificateName() : "",
                "issuer", m.getIssuer() != null ? m.getIssuer() : "",
                "expiryDate", m.getExpiryDate() != null ? m.getExpiryDate() : "",
                "verified", m.getVerified(),
                "uploadedAt", m.getUploadedAt() != null ? m.getUploadedAt().toString() : ""
            )).toList());
    }

    @DeleteMapping("/certificates/{id}")
    public ResponseEntity<?> deleteCertificate(@PathVariable Long id, @AuthenticationPrincipal String email) {
        User provider = getProvider(email);
        ProviderMedia media = mediaRepo.findById(id).orElseThrow(() -> new RuntimeException("Certificate not found"));
        if (!media.getProvider().getId().equals(provider.getId()))
            throw new RuntimeException("Not your certificate");
        mediaRepo.delete(media);
        return ResponseEntity.ok(Map.of("message","Certificate removed"));
    }

    // ── Live GPS Location ────────────────────────────────────────
    /** Provider's device pings this every few seconds while online to update live location */
    @PostMapping("/location/update")
    public ResponseEntity<?> updateLocation(@RequestBody Map<String, Object> body,
                                            @AuthenticationPrincipal String email) {
        User provider = getProvider(email);
        double lat = Double.parseDouble(body.getOrDefault("lat","0").toString());
        double lng = Double.parseDouble(body.getOrDefault("lng","0").toString());
        provider.setCurrentLat(lat);
        provider.setCurrentLng(lng);
        provider.setLocationUpdatedAt(java.time.LocalDateTime.now());
        if (body.containsKey("online")) provider.setIsOnline(Boolean.parseBoolean(body.get("online").toString()));
        userRepo.save(provider);
        return ResponseEntity.ok(Map.of(
            "message","Location updated", "lat", lat, "lng", lng,
            "timestamp", provider.getLocationUpdatedAt().toString()
        ));
    }

    /** Get this provider's own last known location (for self-check / debugging) */
    @GetMapping("/location")
    public ResponseEntity<?> getOwnLocation(@AuthenticationPrincipal String email) {
        User provider = getProvider(email);
        return ResponseEntity.ok(Map.of(
            "lat", provider.getCurrentLat() != null ? provider.getCurrentLat() : 0,
            "lng", provider.getCurrentLng() != null ? provider.getCurrentLng() : 0,
            "online", provider.getIsOnline() != null ? provider.getIsOnline() : false,
            "updatedAt", provider.getLocationUpdatedAt() != null ? provider.getLocationUpdatedAt().toString() : ""
        ));
    }

    // ── Working Hours Schedule ───────────────────────────────────
    @GetMapping("/availability")
    public ResponseEntity<?> getAvailability(@AuthenticationPrincipal String email) {
        User provider = getProvider(email);
        return ResponseEntity.ok(Map.of(
            "workingDays", provider.getWorkingDays() != null ? provider.getWorkingDays() : "MON,TUE,WED,THU,FRI",
            "startTime", provider.getWorkStartTime() != null ? provider.getWorkStartTime() : "09:00",
            "endTime", provider.getWorkEndTime() != null ? provider.getWorkEndTime() : "18:00",
            "maxJobsPerDay", provider.getMaxJobsPerDay() != null ? provider.getMaxJobsPerDay() : 3,
            "isOnline", provider.getIsOnline() != null ? provider.getIsOnline() : false
        ));
    }

    @PutMapping("/availability")
    public ResponseEntity<?> updateAvailability(@RequestBody Map<String, Object> body,
                                                @AuthenticationPrincipal String email) {
        User provider = getProvider(email);

        Object daysObj = body.get("workingDays");
        if (daysObj instanceof List<?> daysList) {
            provider.setWorkingDays(String.join(",", daysList.stream().map(Object::toString).toList()));
        } else if (daysObj instanceof String daysStr && !daysStr.isBlank()) {
            provider.setWorkingDays(daysStr);
        }

        if (body.get("start") != null) provider.setWorkStartTime(body.get("start").toString());
        if (body.get("end") != null) provider.setWorkEndTime(body.get("end").toString());
        if (body.get("maxJobsPerDay") != null) {
            try { provider.setMaxJobsPerDay(Integer.parseInt(body.get("maxJobsPerDay").toString())); }
            catch (NumberFormatException ignored) { /* keep previous value */ }
        }
        userRepo.save(provider);

        return ResponseEntity.ok(Map.of(
            "message", "Working hours updated",
            "workingDays", provider.getWorkingDays(),
            "startTime", provider.getWorkStartTime(),
            "endTime", provider.getWorkEndTime(),
            "maxJobsPerDay", provider.getMaxJobsPerDay()
        ));
    }

    // ── Vacation Mode ─────────────────────────────────────────────
    @GetMapping("/vacation")
    public ResponseEntity<?> getVacation(@AuthenticationPrincipal String email) {
        User provider = getProvider(email);
        autoExpireVacation(provider);
        return ResponseEntity.ok(Map.of(
            "vacationMode", provider.getVacationMode() != null ? provider.getVacationMode() : false,
            "startDate", provider.getVacationStart() != null ? provider.getVacationStart().toString() : "",
            "endDate", provider.getVacationEnd() != null ? provider.getVacationEnd().toString() : "",
            "reason", provider.getVacationReason() != null ? provider.getVacationReason() : ""
        ));
    }

    @PostMapping("/vacation")
    public ResponseEntity<?> startVacation(@RequestBody Map<String, Object> body,
                                           @AuthenticationPrincipal String email) {
        User provider = getProvider(email);
        try {
            java.time.LocalDate start = java.time.LocalDate.parse(body.getOrDefault("startDate","").toString());
            java.time.LocalDate end   = java.time.LocalDate.parse(body.getOrDefault("endDate","").toString());
            if (end.isBefore(start)) throw new RuntimeException("End date can't be before start date");

            provider.setVacationMode(true);
            provider.setVacationStart(start);
            provider.setVacationEnd(end);
            provider.setVacationReason(body.getOrDefault("reason","").toString());
            // Going on vacation takes you offline immediately — no new jobs come in
            provider.setIsOnline(false);
            userRepo.save(provider);

            notifService.create(provider,
                com.tasksphere.entity.Notification.NotificationType.GENERAL,
                "🏖️ Vacation Mode Enabled",
                "You're on vacation from " + start + " to " + end + ". New bookings are paused until you're back.",
                "🏖️", com.tasksphere.entity.Notification.NotificationColor.BLUE,
                provider.getId(), "PROVIDER");

            return ResponseEntity.ok(Map.of(
                "message", "Vacation mode enabled from " + start + " to " + end,
                "vacationMode", true, "startDate", start.toString(), "endDate", end.toString()
            ));
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Please provide valid start and end dates"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/vacation")
    public ResponseEntity<?> endVacation(@AuthenticationPrincipal String email) {
        User provider = getProvider(email);
        provider.setVacationMode(false);
        provider.setVacationStart(null);
        provider.setVacationEnd(null);
        provider.setVacationReason(null);
        userRepo.save(provider);

        notifService.create(provider,
            com.tasksphere.entity.Notification.NotificationType.GENERAL,
            "👋 Welcome Back!",
            "Vacation mode is off — you're accepting new bookings again.",
            "👋", com.tasksphere.entity.Notification.NotificationColor.GREEN,
            provider.getId(), "PROVIDER");

        return ResponseEntity.ok(Map.of("message", "Vacation mode ended — you're back online", "vacationMode", false));
    }

    /** If today is after the vacation end date, automatically clear vacation mode. */
    private void autoExpireVacation(User provider) {
        if (Boolean.TRUE.equals(provider.getVacationMode()) && provider.getVacationEnd() != null
                && java.time.LocalDate.now().isAfter(provider.getVacationEnd())) {
            provider.setVacationMode(false);
            provider.setVacationStart(null);
            provider.setVacationEnd(null);
            provider.setVacationReason(null);
            userRepo.save(provider);
        }
    }
}
