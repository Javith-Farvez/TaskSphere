package com.tasksphere.controller;

import com.tasksphere.dto.BookingDtos.*;
import com.tasksphere.dto.ReviewDtos;
import com.tasksphere.entity.Booking;
import com.tasksphere.entity.Review;
import com.tasksphere.entity.User;
import com.tasksphere.repository.BookingRepository;
import com.tasksphere.repository.FavoriteProviderRepository;
import com.tasksphere.repository.ReviewRepository;
import com.tasksphere.repository.UserRepository;
import com.tasksphere.service.BookingService;
import com.tasksphere.service.NotificationService;
import com.tasksphere.service.ProviderSummaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customer")
public class CustomerController {

    @Autowired private BookingService              bookingService;
    @Autowired private NotificationService          notifService;
    @Autowired private UserRepository               userRepo;
    @Autowired private BookingRepository            bookingRepo;
    @Autowired private ReviewRepository             reviewRepo;
    @Autowired private FavoriteProviderRepository   favoriteRepo;
    @Autowired private ProviderSummaryService       providerSummaryService;
    @Autowired private com.tasksphere.repository.ComplaintRepository complaintRepo;
    @Autowired private com.tasksphere.repository.UserAddressRepository addressRepo;
    @Autowired private com.tasksphere.repository.PaymentRepository paymentRepo;
    @Autowired private com.tasksphere.service.RazorpayService razorpayService;
    @Autowired private com.tasksphere.service.InvoiceService invoiceService;
    @Autowired private com.tasksphere.service.GoogleMapsService mapsService;
    @Autowired private com.tasksphere.service.SseEmitterRegistry sseRegistry;

    // ── Profile ──────────────────────────────────────────────────
    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(@AuthenticationPrincipal String email) {
        User user = userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("name", user.getName());
        result.put("email", user.getEmail());
        result.put("phone", user.getPhone());
        return ResponseEntity.ok(result);
    }

    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(@RequestBody Map<String, String> body,
                                            @AuthenticationPrincipal String email) {
        User user = userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
        if (body.containsKey("firstName") && body.containsKey("lastName"))
            user.setName((body.get("firstName") + " " + body.get("lastName")).trim());
        else if (body.containsKey("name") && !body.get("name").isBlank())
            user.setName(body.get("name"));
        if (body.containsKey("phone")) user.setPhone(body.get("phone"));
        userRepo.save(user);
        return ResponseEntity.ok(Map.of("message", "Profile updated", "name", user.getName()));
    }

    // ── Bookings ──────────────────────────────────────────────────
    @GetMapping("/bookings")
    public ResponseEntity<?> myBookings(@AuthenticationPrincipal String email) {
        return ResponseEntity.ok(bookingService.getMyBookings(email));
    }

    @PostMapping("/bookings")
    public ResponseEntity<?> createBooking(@RequestBody CreateRequest req,
                                           @AuthenticationPrincipal String email) {
        try {
            return ResponseEntity.ok(bookingService.create(req, email));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PatchMapping("/bookings/{id}/cancel")
    public ResponseEntity<?> cancelBooking(@PathVariable Long id,
                                           @AuthenticationPrincipal String email) {
        try {
            return ResponseEntity.ok(bookingService.cancel(id, email));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** Reschedule Booking — capped at 2 reschedules, blocked once en-route/in-progress */
    @PatchMapping("/bookings/{id}/reschedule")
    public ResponseEntity<?> rescheduleBooking(@PathVariable Long id,
                                               @RequestBody RescheduleRequest req,
                                               @AuthenticationPrincipal String email) {
        try {
            return ResponseEntity.ok(bookingService.reschedule(id, req.getDate(), req.getSlot(), email));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ── Favorite Providers ──────────────────────────────────────────
    @GetMapping("/favorites")
    public ResponseEntity<?> myFavorites(@AuthenticationPrincipal String email) {
        User customer = userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Customer not found"));
        var favorites = favoriteRepo.findByCustomerOrderByCreatedAtDesc(customer);
        var summaries = favorites.stream()
                .map(f -> providerSummaryService.build(f.getProvider(), customer))
                .toList();
        return ResponseEntity.ok(summaries);
    }

    @PostMapping("/favorites/{providerId}")
    public ResponseEntity<?> addFavorite(@PathVariable Long providerId,
                                         @AuthenticationPrincipal String email) {
        try {
            User customer = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("Customer not found"));
            User provider = userRepo.findById(providerId)
                    .orElseThrow(() -> new RuntimeException("Provider not found"));
            if (provider.getRole() != User.Role.PROVIDER)
                throw new RuntimeException("This user is not a provider");
            if (favoriteRepo.existsByCustomerAndProvider(customer, provider))
                return ResponseEntity.ok(Map.of("message", "Already in favorites", "favorited", true));

            favoriteRepo.save(com.tasksphere.entity.FavoriteProvider.builder()
                    .customer(customer).provider(provider).build());
            return ResponseEntity.ok(Map.of("message", provider.getName() + " added to favorites", "favorited", true));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/favorites/{providerId}")
    public ResponseEntity<?> removeFavorite(@PathVariable Long providerId,
                                            @AuthenticationPrincipal String email) {
        try {
            User customer = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("Customer not found"));
            User provider = userRepo.findById(providerId)
                    .orElseThrow(() -> new RuntimeException("Provider not found"));
            favoriteRepo.deleteByCustomerAndProvider(customer, provider);
            return ResponseEntity.ok(Map.of("message", "Removed from favorites", "favorited", false));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ── Reviews: Add / Edit / Delete (fires RATING_RECEIVED notification) ─

    /** Add Review — one review per completed booking */
    @PostMapping("/bookings/{id}/review")
    public ResponseEntity<?> submitReview(@PathVariable Long id,
                                          @RequestBody ReviewDtos.CreateRequest req,
                                          @AuthenticationPrincipal String email) {
        try {
            User customer = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("Customer not found"));
            Booking booking = bookingRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Booking not found"));

            if (booking.getCustomer() == null || !booking.getCustomer().getId().equals(customer.getId()))
                throw new RuntimeException("This booking does not belong to you");
            if (booking.getStatus() != Booking.BookingStatus.COMPLETED)
                throw new RuntimeException("You can only review completed bookings");
            if (reviewRepo.existsByBookingId(id))
                throw new RuntimeException("You've already reviewed this booking. Try editing it instead.");

            int rating = clampRating(req.getRating());
            String comment = req.getComment() != null ? req.getComment().trim() : "";

            Review review = Review.builder()
                    .customer(customer)
                    .provider(booking.getProvider())
                    .booking(booking)
                    .rating(rating)
                    .comment(comment)
                    .build();
            reviewRepo.save(review);

            // ── Notify provider of new rating ──────────────────────
            if (booking.getProvider() != null) {
                notifService.onRatingReceived(
                    booking.getProvider(),
                    rating,
                    customer.getName(),
                    booking.getService()
                );
            }

            return ResponseEntity.ok(ReviewDtos.ReviewResponse.from(review));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** List my reviews */
    @GetMapping("/reviews")
    public ResponseEntity<?> myReviews(@AuthenticationPrincipal String email) {
        User customer = userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Customer not found"));
        return ResponseEntity.ok(
            reviewRepo.findByCustomerOrderByCreatedAtDesc(customer)
                .stream().map(ReviewDtos.ReviewResponse::from).toList()
        );
    }

    /** Edit Review — owner only, rating/comment editable */
    @PutMapping("/reviews/{reviewId}")
    public ResponseEntity<?> editReview(@PathVariable Long reviewId,
                                        @RequestBody ReviewDtos.UpdateRequest req,
                                        @AuthenticationPrincipal String email) {
        try {
            User customer = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("Customer not found"));
            Review review = reviewRepo.findById(reviewId)
                    .orElseThrow(() -> new RuntimeException("Review not found"));
            if (review.getCustomer() == null || !review.getCustomer().getId().equals(customer.getId()))
                throw new RuntimeException("You can only edit your own review");

            if (req.getRating() != null) review.setRating(clampRating(req.getRating()));
            if (req.getComment() != null) review.setComment(req.getComment().trim());
            review.setEdited(true);
            review.setUpdatedAt(java.time.LocalDateTime.now());
            reviewRepo.save(review);

            return ResponseEntity.ok(ReviewDtos.ReviewResponse.from(review));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** Delete Review — owner only */
    @DeleteMapping("/reviews/{reviewId}")
    public ResponseEntity<?> deleteReview(@PathVariable Long reviewId,
                                          @AuthenticationPrincipal String email) {
        try {
            User customer = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("Customer not found"));
            Review review = reviewRepo.findById(reviewId)
                    .orElseThrow(() -> new RuntimeException("Review not found"));
            if (review.getCustomer() == null || !review.getCustomer().getId().equals(customer.getId()))
                throw new RuntimeException("You can only delete your own review");

            reviewRepo.delete(review);
            return ResponseEntity.ok(Map.of("message", "Review deleted"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private int clampRating(Integer rating) {
        int r = rating != null ? rating : 5;
        return Math.max(1, Math.min(5, r));
    }

    // ── Share customer location ───────────────────────────────────
    // Persists the customer's live GPS onto their own User row (reusing the
    // same currentLat/currentLng columns providers use), and — if they have
    // an active booking with a provider assigned — pushes the update to
    // that provider in real time over SSE, so the provider's live map can
    // show exactly where the customer is while en route.
    @PostMapping("/location")
    public ResponseEntity<?> shareLocation(@RequestBody Map<String, Object> body,
                                           @AuthenticationPrincipal String email) {
        try {
            User customer = userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("Customer not found"));
            double lat = Double.parseDouble(String.valueOf(body.getOrDefault("lat", "0")));
            double lng = Double.parseDouble(String.valueOf(body.getOrDefault("lng", "0")));
            customer.setCurrentLat(lat);
            customer.setCurrentLng(lng);
            customer.setLocationUpdatedAt(java.time.LocalDateTime.now());
            userRepo.save(customer);

            bookingRepo.findAll().stream()
                    .filter(b -> b.getCustomer() != null && b.getCustomer().getId().equals(customer.getId())
                            && b.getProvider() != null
                            && (b.getStatus() == Booking.BookingStatus.CONFIRMED
                                || b.getStatus() == Booking.BookingStatus.EN_ROUTE
                                || b.getStatus() == Booking.BookingStatus.IN_PROGRESS))
                    .forEach(b -> sseRegistry.send(b.getProvider().getId(), "customer-location",
                            Map.of("bookingId", b.getId(), "lat", lat, "lng", lng)));

            return ResponseEntity.ok(Map.of("message", "Location received", "lat", lat, "lng", lng));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ── Track assigned provider's live GPS for a booking ───────────
    @GetMapping("/bookings/{id}/track")
    public ResponseEntity<?> trackProvider(@PathVariable Long id,
                                           @AuthenticationPrincipal String email) {
        User customer = userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("Customer not found"));
        Booking booking = bookingRepo.findById(id).orElseThrow(() -> new RuntimeException("Booking not found"));
        if (booking.getCustomer() == null || !booking.getCustomer().getId().equals(customer.getId()))
            throw new RuntimeException("This booking does not belong to you");
        if (booking.getProvider() == null)
            return ResponseEntity.ok(Map.of("message","No provider assigned yet","assigned", false));

        User provider = booking.getProvider();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("assigned", true);
        result.put("providerName", provider.getName());
        result.put("lat", provider.getCurrentLat() != null ? provider.getCurrentLat() : 0);
        result.put("lng", provider.getCurrentLng() != null ? provider.getCurrentLng() : 0);
        result.put("online", provider.getIsOnline() != null ? provider.getIsOnline() : false);
        result.put("lastUpdated", provider.getLocationUpdatedAt() != null ? provider.getLocationUpdatedAt().toString() : "");
        result.put("bookingStatus", booking.getStatus().name());

        // Real rating from actual submitted reviews — never a hardcoded
        // "4.9". null/0 reviews means genuinely no ratings yet.
        long reviewCount = reviewRepo.countByProvider(provider);
        result.put("providerRating", reviewCount > 0 ? Math.round(reviewRepo.avgRatingByProvider(provider) * 10.0) / 10.0 : null);
        result.put("providerReviewCount", reviewCount);
        result.put("hasReview", reviewRepo.existsByBookingId(booking.getId()));

        // Destination = the ACTUAL address the customer booked the job for —
        // geocoded so it lines up exactly with what Google Maps shows for that
        // address, not just wherever the customer's phone GPS happens to be
        // sitting right now. Falls back to their last-shared live GPS only if
        // the address can't be geocoded (Maps not configured / bad address).
        Double destLat = null, destLng = null;
        if (booking.getAddress() != null && !booking.getAddress().isBlank()) {
            Map<String, Double> geo = mapsService.geocode(booking.getAddress());
            if (geo != null) { destLat = geo.get("lat"); destLng = geo.get("lng"); }
        }
        if (destLat == null && customer.getCurrentLat() != null && customer.getCurrentLng() != null) {
            destLat = customer.getCurrentLat(); destLng = customer.getCurrentLng();
        }
        if (destLat != null) {
            result.put("customerLat", destLat);
            result.put("customerLng", destLng);
        }

        // Real distance + ETA from provider's live GPS to the booked address
        // (falls back gracefully if either side hasn't shared/geocoded a location yet)
        if (provider.getCurrentLat() != null && provider.getCurrentLng() != null && destLat != null) {
            result.putAll(mapsService.distanceAndEta(
                    provider.getCurrentLat(), provider.getCurrentLng(),
                    destLat, destLng));
        }

        return ResponseEntity.ok(result);
    }

    // ── Complaints ───────────────────────────────────────────────
    @PostMapping("/complaints")
    public ResponseEntity<?> fileComplaint(@AuthenticationPrincipal String email,
                                            @RequestBody com.tasksphere.dto.ComplaintDtos.ComplaintRequest req) {
        User customer = userRepo.findByEmail(email).orElseThrow();
        com.tasksphere.entity.Booking booking = req.getBookingId() != null
                ? bookingRepo.findById(req.getBookingId()).orElse(null) : null;

        com.tasksphere.entity.Complaint complaint = com.tasksphere.entity.Complaint.builder()
                .customer(customer)
                .provider(booking != null ? booking.getProvider() : null)
                .booking(booking)
                .subject(req.getSubject())
                .description(req.getDescription())
                .priority(req.getPriority() != null
                        ? com.tasksphere.entity.Complaint.ComplaintPriority.valueOf(req.getPriority().toUpperCase())
                        : com.tasksphere.entity.Complaint.ComplaintPriority.MEDIUM)
                .build();
        complaintRepo.save(complaint);
        return ResponseEntity.ok(Map.of("message", "Complaint submitted. Our team will review it shortly.", "id", complaint.getId()));
    }

    @GetMapping("/complaints")
    public ResponseEntity<?> myComplaints(@AuthenticationPrincipal String email) {
        User customer = userRepo.findByEmail(email).orElseThrow();
        return ResponseEntity.ok(complaintRepo.findByCustomerOrderByCreatedAtDesc(customer).stream().map(c -> Map.of(
                "id", c.getId(), "subject", c.getSubject(), "description", c.getDescription(),
                "status", c.getStatus().name(), "priority", c.getPriority().name(),
                "adminResponse", c.getAdminResponse() != null ? c.getAdminResponse() : "",
                "createdAt", c.getCreatedAt().toString()
        )).toList());
    }

    // ── Saved Addresses ──────────────────────────────────────────
    @GetMapping("/addresses")
    public ResponseEntity<?> myAddresses(@AuthenticationPrincipal String email) {
        User customer = userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("Customer not found"));
        return ResponseEntity.ok(
            addressRepo.findByCustomerOrderByIsDefaultDescCreatedAtDesc(customer)
                .stream().map(com.tasksphere.dto.UserAddressDtos.AddressResponse::from).toList()
        );
    }

    @PostMapping("/addresses")
    public ResponseEntity<?> addAddress(@RequestBody com.tasksphere.dto.UserAddressDtos.AddressRequest req,
                                        @AuthenticationPrincipal String email) {
        try {
            User customer = userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("Customer not found"));
            if (req.getAddressLine() == null || req.getAddressLine().isBlank())
                throw new RuntimeException("Address line is required");

            boolean firstAddress = addressRepo.countByCustomer(customer) == 0;
            boolean makeDefault = Boolean.TRUE.equals(req.getIsDefault()) || firstAddress; // first saved address is default automatically
            if (makeDefault) clearExistingDefault(customer);

            com.tasksphere.entity.UserAddress addr = com.tasksphere.entity.UserAddress.builder()
                    .customer(customer)
                    .label(req.getLabel() != null && !req.getLabel().isBlank() ? req.getLabel() : "Home")
                    .addressLine(req.getAddressLine().trim())
                    .city(req.getCity())
                    .state(req.getState())
                    .pincode(req.getPincode())
                    .landmark(req.getLandmark())
                    .phone(req.getPhone())
                    .lat(req.getLat())
                    .lng(req.getLng())
                    .isDefault(makeDefault)
                    .build();
            addressRepo.save(addr);
            return ResponseEntity.ok(com.tasksphere.dto.UserAddressDtos.AddressResponse.from(addr));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/addresses/{id}")
    public ResponseEntity<?> editAddress(@PathVariable Long id,
                                         @RequestBody com.tasksphere.dto.UserAddressDtos.AddressRequest req,
                                         @AuthenticationPrincipal String email) {
        try {
            User customer = userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("Customer not found"));
            com.tasksphere.entity.UserAddress addr = addressRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Address not found"));
            if (addr.getCustomer() == null || !addr.getCustomer().getId().equals(customer.getId()))
                throw new RuntimeException("You can only edit your own address");

            if (req.getLabel() != null) addr.setLabel(req.getLabel());
            if (req.getAddressLine() != null) addr.setAddressLine(req.getAddressLine().trim());
            if (req.getCity() != null) addr.setCity(req.getCity());
            if (req.getState() != null) addr.setState(req.getState());
            if (req.getPincode() != null) addr.setPincode(req.getPincode());
            if (req.getLandmark() != null) addr.setLandmark(req.getLandmark());
            if (req.getPhone() != null) addr.setPhone(req.getPhone());
            if (req.getLat() != null) addr.setLat(req.getLat());
            if (req.getLng() != null) addr.setLng(req.getLng());
            if (Boolean.TRUE.equals(req.getIsDefault()) && !Boolean.TRUE.equals(addr.getIsDefault())) {
                clearExistingDefault(customer);
                addr.setIsDefault(true);
            }
            addressRepo.save(addr);
            return ResponseEntity.ok(com.tasksphere.dto.UserAddressDtos.AddressResponse.from(addr));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/addresses/{id}")
    public ResponseEntity<?> deleteAddress(@PathVariable Long id, @AuthenticationPrincipal String email) {
        try {
            User customer = userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("Customer not found"));
            com.tasksphere.entity.UserAddress addr = addressRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Address not found"));
            if (addr.getCustomer() == null || !addr.getCustomer().getId().equals(customer.getId()))
                throw new RuntimeException("You can only delete your own address");
            boolean wasDefault = Boolean.TRUE.equals(addr.getIsDefault());
            addressRepo.delete(addr);

            // Promote the most recently added remaining address to default, if needed
            if (wasDefault) {
                addressRepo.findByCustomerOrderByIsDefaultDescCreatedAtDesc(customer).stream().findFirst()
                        .ifPresent(next -> { next.setIsDefault(true); addressRepo.save(next); });
            }
            return ResponseEntity.ok(Map.of("message", "Address deleted"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PatchMapping("/addresses/{id}/default")
    public ResponseEntity<?> setDefaultAddress(@PathVariable Long id, @AuthenticationPrincipal String email) {
        try {
            User customer = userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("Customer not found"));
            com.tasksphere.entity.UserAddress addr = addressRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Address not found"));
            if (addr.getCustomer() == null || !addr.getCustomer().getId().equals(customer.getId()))
                throw new RuntimeException("You can only update your own address");
            clearExistingDefault(customer);
            addr.setIsDefault(true);
            addressRepo.save(addr);
            return ResponseEntity.ok(com.tasksphere.dto.UserAddressDtos.AddressResponse.from(addr));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private void clearExistingDefault(User customer) {
        addressRepo.findByCustomerAndIsDefaultTrue(customer).ifPresent(existing -> {
            existing.setIsDefault(false);
            addressRepo.save(existing);
        });
    }

    // ── Payments ──────────────────────────────────────────────────
    /** Creates a real Razorpay order (secure flow) before opening checkout. */
    @PostMapping("/payments/create-order")
    public ResponseEntity<?> createPaymentOrder(@RequestBody Map<String, Object> body,
                                                @AuthenticationPrincipal String email) {
        try {
            User customer = userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("Customer not found"));
            double amount = Double.parseDouble(String.valueOf(body.getOrDefault("amount", "0")));
            if (amount <= 0) throw new RuntimeException("Invalid amount");
            String receipt = "TS_" + System.currentTimeMillis();
            Map<String, Object> order = razorpayService.createOrder(amount, receipt);

            // Record a PENDING placeholder immediately — this is what lets the
            // Razorpay webhook (server-to-server Payment Success/Failure
            // Callback) find and update the right row even if it arrives
            // before the frontend finishes creating the booking.
            double fee = Math.round(amount * 0.08 * 100.0) / 100.0;
            com.tasksphere.entity.Payment placeholder = com.tasksphere.entity.Payment.builder()
                    .customer(customer)
                    .razorpayRef("ORDER_" + order.get("orderId"))
                    .razorpayOrderId(String.valueOf(order.get("orderId")))
                    .amount(amount)
                    .platformFee(fee)
                    .netAmount(amount - fee)
                    .paymentMethod("Razorpay")
                    .customerName(customer.getName())
                    .type(com.tasksphere.entity.Payment.PaymentType.CREDIT)
                    .status(com.tasksphere.entity.Payment.PaymentStatus.PENDING)
                    .build();
            paymentRepo.save(placeholder);

            return ResponseEntity.ok(order);
        } catch (IllegalStateException e) {
            // Razorpay not configured yet — clear, honest error instead of fake success
            return ResponseEntity.status(503).body(Map.of("message", e.getMessage(), "configured", false));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** Verify Payment Signature — standalone, explicitly callable endpoint
     *  (also used internally by booking creation and the webhook handler). */
    @PostMapping("/payments/verify")
    public ResponseEntity<?> verifyPaymentSignature(@RequestBody Map<String, Object> body,
                                                     @AuthenticationPrincipal String email) {
        String orderId = String.valueOf(body.getOrDefault("razorpayOrderId", ""));
        String paymentId = String.valueOf(body.getOrDefault("razorpayPaymentId", ""));
        String signature = String.valueOf(body.getOrDefault("razorpaySignature", ""));
        if (!razorpayService.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of("verified", false,
                    "message", "Razorpay not configured yet — cannot verify signatures."));
        }
        boolean valid = razorpayService.verifySignature(orderId, paymentId, signature);
        return ResponseEntity.ok(Map.of("verified", valid));
    }

    /** Payment Success Callback — frontend calls this the instant Razorpay's
     *  checkout handler fires, BEFORE creating the booking. Verifies the
     *  signature and marks the pending payment PAID immediately, so the
     *  transaction is recorded even if the booking-creation call that
     *  follows fails or the user closes the tab. */
    @PostMapping("/payments/success-callback")
    public ResponseEntity<?> paymentSuccessCallback(@RequestBody Map<String, Object> body,
                                                     @AuthenticationPrincipal String email) {
        try {
            User customer = userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("Customer not found"));
            String orderId = String.valueOf(body.getOrDefault("razorpayOrderId", ""));
            String paymentId = String.valueOf(body.getOrDefault("razorpayPaymentId", ""));
            String signature = String.valueOf(body.getOrDefault("razorpaySignature", ""));

            if (razorpayService.isConfigured()) {
                boolean valid = razorpayService.verifySignature(orderId, paymentId, signature);
                if (!valid) {
                    return ResponseEntity.badRequest().body(Map.of("verified", false, "message", "Signature verification failed"));
                }
            }

            var existing = paymentRepo.findByRazorpayOrderId(orderId);
            if (existing.isPresent()) {
                com.tasksphere.entity.Payment p = existing.get();
                p.setRazorpayRef(paymentId);
                p.setRazorpaySignature(signature);
                p.setStatus(com.tasksphere.entity.Payment.PaymentStatus.PAID);
                paymentRepo.save(p);
            }
            return ResponseEntity.ok(Map.of("verified", true, "message", "Payment confirmed"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** Payment Failure Callback — frontend calls this from Razorpay's
     *  `payment.failed` handler. Records the failed attempt (previously this
     *  was only shown as a toast and never reached the backend at all) and
     *  notifies the customer. */
    @PostMapping("/payments/failure-callback")
    public ResponseEntity<?> paymentFailureCallback(@RequestBody Map<String, Object> body,
                                                     @AuthenticationPrincipal String email) {
        try {
            User customer = userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("Customer not found"));
            String orderId = body.get("razorpayOrderId") != null ? String.valueOf(body.get("razorpayOrderId")) : null;
            String reason = String.valueOf(body.getOrDefault("reason", "Payment failed"));

            com.tasksphere.entity.Payment payment;
            if (orderId != null && paymentRepo.findByRazorpayOrderId(orderId).isPresent()) {
                payment = paymentRepo.findByRazorpayOrderId(orderId).get();
                payment.setStatus(com.tasksphere.entity.Payment.PaymentStatus.FAILED);
                payment.setNote(reason);
            } else {
                double amount = Double.parseDouble(String.valueOf(body.getOrDefault("amount", "0")));
                payment = com.tasksphere.entity.Payment.builder()
                        .customer(customer)
                        .razorpayRef("FAILED_" + System.currentTimeMillis())
                        .razorpayOrderId(orderId)
                        .amount(amount)
                        .platformFee(0.0).netAmount(0.0)
                        .paymentMethod("Razorpay")
                        .customerName(customer.getName())
                        .serviceName(body.get("service") != null ? String.valueOf(body.get("service")) : null)
                        .note(reason)
                        .type(com.tasksphere.entity.Payment.PaymentType.CREDIT)
                        .status(com.tasksphere.entity.Payment.PaymentStatus.FAILED)
                        .build();
            }
            paymentRepo.save(payment);

            notifService.create(customer,
                    com.tasksphere.entity.Notification.NotificationType.PAYMENT_FAILED,
                    "Payment Failed ⚠️",
                    "Your payment of ₹" + String.format("%.0f", payment.getAmount()) + " could not be completed. " + reason,
                    "⚠️", com.tasksphere.entity.Notification.NotificationColor.RED,
                    payment.getId(), "PAYMENT");

            return ResponseEntity.ok(Map.of("recorded", true));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** Payment history for the logged-in customer, driven by the Payment (PaymentHistory) table. */
    @GetMapping("/payments")
    public ResponseEntity<?> myPayments(@AuthenticationPrincipal String email) {
        User customer = userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("Customer not found"));
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");
        return ResponseEntity.ok(paymentRepo.findByBookingCustomer(customer).stream().map(p -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("bookingId", p.getBooking() != null ? p.getBooking().getId() : null);
            m.put("ref", p.getRazorpayRef());
            m.put("service", p.getServiceName());
            m.put("amount", p.getAmount());
            m.put("platformFee", p.getPlatformFee());
            m.put("method", p.getPaymentMethod());
            m.put("status", p.getStatus().name());
            m.put("date", p.getCreatedAt() != null ? p.getCreatedAt().format(fmt) : "-");
            return m;
        }).toList());
    }

    /** Downloads a real PDF invoice for a booking the logged-in customer owns. */
    @GetMapping("/payments/{bookingId}/invoice")
    public ResponseEntity<?> downloadInvoice(@PathVariable Long bookingId, @AuthenticationPrincipal String email) {
        try {
            User customer = userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("Customer not found"));
            Booking booking = bookingRepo.findById(bookingId).orElseThrow(() -> new RuntimeException("Booking not found"));
            if (booking.getCustomer() == null || !booking.getCustomer().getId().equals(customer.getId())) {
                throw new RuntimeException("You can only download invoices for your own bookings");
            }
            byte[] pdf = invoiceService.generateInvoicePdf(booking);
            return ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"TaskSphere_Invoice_" + booking.getId() + ".pdf\"")
                    .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (RuntimeException | java.io.IOException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ── Google Maps: geocode a typed address to lat/lng ─────────────
    @PostMapping("/geocode")
    public ResponseEntity<?> geocode(@RequestBody Map<String, Object> body) {
        String address = String.valueOf(body.getOrDefault("address", ""));
        Map<String, Double> coords = mapsService.geocode(address);
        if (coords == null) {
            return ResponseEntity.ok(Map.of("geocoded", false,
                    "message", mapsService.isConfigured() ? "Could not geocode this address" : "Google Maps not configured yet"));
        }
        return ResponseEntity.ok(Map.of("geocoded", true, "lat", coords.get("lat"), "lng", coords.get("lng")));
    }

    // ── Nearest Provider Algorithm — real GPS-ranked provider list ──
    @GetMapping("/providers/nearby")
    public ResponseEntity<?> nearbyProviders(@RequestParam(required = false) String category,
                                             @RequestParam(required = false) Double lat,
                                             @RequestParam(required = false) Double lng,
                                             @RequestParam(defaultValue = "5") int limit,
                                             @AuthenticationPrincipal String email) {
        User customer = userRepo.findByEmail(email).orElseThrow(() -> new RuntimeException("Customer not found"));
        double useLat = lat != null ? lat : (customer.getCurrentLat() != null ? customer.getCurrentLat() : 0);
        double useLng = lng != null ? lng : (customer.getCurrentLng() != null ? customer.getCurrentLng() : 0);
        if (useLat == 0 && useLng == 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "No location available — share your location or pass lat/lng"));
        }

        List<User> candidateProviders = category != null && !category.isBlank()
                ? bookingService.providersForCategory(category)
                : userRepo.findByRoleAndStatus(User.Role.PROVIDER, User.Status.ACTIVE);

        var ranked = mapsService.rankByProximity(candidateProviders, useLat, useLng, limit);
        var out = ranked.stream().map(m -> {
            User p = (User) m.get("provider");
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("id", p.getId());
            r.put("name", p.getName());
            r.put("online", p.getIsOnline() != null ? p.getIsOnline() : false);
            r.put("rating", round1(safe(reviewRepo.avgRatingByProvider(p))));
            r.put("distanceKm", m.get("distanceKm"));
            r.put("etaMinutes", m.get("etaMinutes"));
            return r;
        }).toList();
        return ResponseEntity.ok(out);
    }

    private double safe(Double d) { return d == null ? 0.0 : d; }
    private double round1(double d) { return Math.round(d * 10.0) / 10.0; }
}
