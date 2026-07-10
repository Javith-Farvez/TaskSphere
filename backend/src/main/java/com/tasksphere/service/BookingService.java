package com.tasksphere.service;

import com.tasksphere.dto.BookingDtos.*;
import com.tasksphere.entity.Booking;
import com.tasksphere.entity.User;
import com.tasksphere.repository.BookingRepository;
import com.tasksphere.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired private BookingRepository     bookingRepo;
    @Autowired private UserRepository        userRepo;
    @Autowired private NotificationService   notifService;
    @Autowired private EmailService          emailService;
    @Autowired private RazorpayService       razorpayService;
    @Autowired private com.tasksphere.repository.PaymentRepository paymentRepo;
    @Autowired private com.tasksphere.repository.ServiceRepository serviceRepo;
    @Autowired private GoogleMapsService     mapsService;
    @Autowired private com.tasksphere.repository.ReviewRepository  reviewRepo;

    public BookingResponse create(CreateRequest req, String customerEmail) {
        User customer = userRepo.findByEmail(customerEmail)
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        User provider = null;
        if (req.getProvider() != null && !req.getProvider().isBlank()) {
            provider = userRepo.findAll().stream()
                    .filter(u -> u.getRole() == User.Role.PROVIDER
                              && u.getName().equalsIgnoreCase(req.getProvider()))
                    .findFirst().orElse(null);
        } else {
            // ── Auto Provider Matching ──────────────────────────────
            // No provider explicitly chosen — assign the nearest currently-
            // online provider for this category using real GPS (Nearest
            // Provider Algorithm), falling back to the best-rated available
            // provider if nobody has shared live location yet.
            List<User> candidates = providersForCategory(req.getService());
            List<User> online = candidates.stream().filter(p -> Boolean.TRUE.equals(p.getIsOnline())).toList();
            List<User> pool = online.isEmpty() ? candidates : online;

            if (customer.getCurrentLat() != null && customer.getCurrentLng() != null && !pool.isEmpty()) {
                var ranked = mapsService.rankByProximity(pool, customer.getCurrentLat(), customer.getCurrentLng(), 1);
                if (!ranked.isEmpty()) provider = (User) ranked.get(0).get("provider");
            }
            if (provider == null && !pool.isEmpty()) {
                provider = pool.get(0); // no GPS available yet — first available match in category
            }
        }

        // Cash on Delivery ("Cash", "COD") is paid in person once the job is
        // done — it must NEVER be marked PAID at booking time. Only a real
        // online method (UPI/Card/Net Banking/Razorpay) can be PAID here.
        boolean cod = isCashMethod(req.getPaymentMethod());

        Booking booking = Booking.builder()
                .customer(customer)
                .provider(provider)
                .service(req.getService())
                .address(req.getAddress())
                .notes(req.getNotes())
                .slot(req.getSlot())
                .amount(req.getAmount() != null ? req.getAmount() : 399.0)
                .paymentMethod(req.getPaymentMethod())
                .paymentRef(resolvePaymentRef(req))
                .status(Booking.BookingStatus.CONFIRMED)
                .paymentStatus(cod ? Booking.PaymentStatus.PENDING : Booking.PaymentStatus.PAID)
                .build();

        Booking saved = bookingRepo.save(booking);

        // ── Real payment verification + PaymentHistory persistence ─────
        // If the client completed a real Razorpay checkout, its order/payment
        // IDs + signature arrive here — verify them before trusting "paid",
        // and record the transaction in the Payment (PaymentHistory) table.
        // Cash on Delivery bookings skip this section entirely — there is no
        // Razorpay transaction to verify or record until the cash is
        // actually collected (see complete()).
        com.tasksphere.entity.Payment paymentRecord = null;
        if (!cod) {
            boolean hasRazorpayFields = req.getRazorpayOrderId() != null && !req.getRazorpayOrderId().isBlank()
                    && req.getRazorpayPaymentId() != null && !req.getRazorpayPaymentId().isBlank()
                    && req.getRazorpaySignature() != null && !req.getRazorpaySignature().isBlank();

            if (hasRazorpayFields) {
                boolean valid = razorpayService.verifySignature(
                        req.getRazorpayOrderId(), req.getRazorpayPaymentId(), req.getRazorpaySignature());
                if (!valid) {
                    saved.setPaymentStatus(Booking.PaymentStatus.FAILED);
                    saved.setStatus(Booking.BookingStatus.CANCELLED);
                    bookingRepo.save(saved);
                    notifService.onPaymentFailed(buildFailedPaymentStub(saved));
                    throw new RuntimeException("Payment verification failed — signature mismatch. Booking not confirmed.");
                }
                paymentRecord = savePaymentHistory(saved, req.getRazorpayPaymentId(), req.getRazorpayOrderId(), req.getRazorpaySignature());
            } else if (provider != null) {
                // Legacy/demo path (no live Razorpay keys yet) — still record a
                // PaymentHistory row so payment history/invoices work end-to-end.
                paymentRecord = savePaymentHistory(saved, saved.getPaymentRef(), null, null);
            }
        }

        // ── Notifications ──────────────────────────────────────────
        notifService.onBookingPlaced(saved);
        notifService.onHighValueBooking(saved);

        // ── Emails ───────────────────────────────────────────────────
        emailService.sendBookingConfirmation(saved);
        if (saved.getPaymentStatus() == Booking.PaymentStatus.PAID) {
            // Real payment received right now — receipt to the customer, and
            // (this was missing before) a payment notification + earnings
            // receipt email to the provider so they actually find out they
            // were paid instead of only the customer being told.
            emailService.sendCustomerPaymentReceipt(saved);
            if (saved.getProvider() != null && paymentRecord != null) {
                notifService.onPaymentReceived(paymentRecord);
                emailService.sendProviderEarningsReceipt(paymentRecord);
            }
        }
        // Cash on Delivery: intentionally no "payment received"/"payment
        // successful" notification or email here — nothing has been paid
        // yet. sendBookingConfirmation() already tells the customer this is
        // a Cash on Delivery booking and how much to keep ready.

        return BookingResponse.from(saved);
    }

    /** Recognizes every "pay in person" label the frontend/UI may send. */
    private boolean isCashMethod(String paymentMethod) {
        if (paymentMethod == null) return false;
        String m = paymentMethod.trim().toLowerCase();
        return m.equals("cash") || m.equals("cod") || m.contains("cash on delivery") || m.contains("cash on arrival");
    }

    /** Active providers offering a service matching this category/name — used by
     *  Auto Provider Matching and the customer-facing "nearby providers" endpoint. */
    public List<User> providersForCategory(String category) {
        if (category == null || category.isBlank()) {
            return userRepo.findByRoleAndStatus(User.Role.PROVIDER, User.Status.ACTIVE);
        }
        List<User> fromListings = serviceRepo.findByCategoryIgnoreCaseAndEnabledTrue(category).stream()
                .map(com.tasksphere.entity.Service::getProvider)
                .filter(p -> p != null && p.getStatus() == User.Status.ACTIVE)
                .distinct()
                .toList();
        if (!fromListings.isEmpty()) return fromListings;
        // No exact listing match yet — fall back to any active provider so
        // a booking can still be auto-assigned on a freshly-seeded database.
        return userRepo.findByRoleAndStatus(User.Role.PROVIDER, User.Status.ACTIVE);
    }

    private String resolvePaymentRef(CreateRequest req) {
        if (req.getRazorpayPaymentId() != null && !req.getRazorpayPaymentId().isBlank()) {
            return req.getRazorpayPaymentId();
        }
        return "TS" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private com.tasksphere.entity.Payment savePaymentHistory(Booking booking, String paymentRef, String orderId, String signature) {
        double fee = Math.round(booking.getAmount() * 0.08 * 100.0) / 100.0;
        double net = booking.getAmount() - fee;

        // If a PENDING placeholder already exists for this order (created at
        // /payments/create-order time), update it instead of inserting a
        // duplicate row — keeps one Payment record per real transaction.
        com.tasksphere.entity.Payment payment = (orderId != null)
                ? paymentRepo.findByRazorpayOrderId(orderId).orElse(null)
                : null;
        if (payment == null) {
            payment = com.tasksphere.entity.Payment.builder()
                    .razorpayRef(paymentRef)
                    .razorpayOrderId(orderId)
                    .build();
        }
        payment.setCustomer(booking.getCustomer());
        payment.setProvider(booking.getProvider());
        payment.setBooking(booking);
        payment.setRazorpayRef(paymentRef);
        payment.setRazorpaySignature(signature);
        payment.setAmount(booking.getAmount());
        payment.setPlatformFee(fee);
        payment.setNetAmount(net);
        payment.setPaymentMethod(booking.getPaymentMethod() != null ? booking.getPaymentMethod() : "Razorpay");
        payment.setCustomerName(booking.getCustomer() != null ? booking.getCustomer().getName() : "Customer");
        payment.setServiceName(booking.getService());
        payment.setType(com.tasksphere.entity.Payment.PaymentType.CREDIT);
        payment.setStatus(com.tasksphere.entity.Payment.PaymentStatus.PAID);
        try {
            return paymentRepo.save(payment);
        } catch (RuntimeException e) {
            // Duplicate razorpayRef (unique constraint) — payment already recorded, safe to ignore
            return payment;
        }
    }

    private com.tasksphere.entity.Payment buildFailedPaymentStub(Booking booking) {
        com.tasksphere.entity.Payment stub = com.tasksphere.entity.Payment.builder()
                .customer(booking.getCustomer())
                .provider(booking.getProvider())
                .booking(booking)
                .razorpayRef("FAILED_" + System.currentTimeMillis())
                .amount(booking.getAmount())
                .platformFee(0.0).netAmount(0.0)
                .paymentMethod(booking.getPaymentMethod() != null ? booking.getPaymentMethod() : "Razorpay")
                .customerName(booking.getCustomer() != null ? booking.getCustomer().getName() : "Customer")
                .serviceName(booking.getService())
                .note("Signature verification failed")
                .type(com.tasksphere.entity.Payment.PaymentType.CREDIT)
                .status(com.tasksphere.entity.Payment.PaymentStatus.FAILED)
                .build();
        try {
            paymentRepo.save(stub); // previously built but never persisted — now actually recorded
        } catch (RuntimeException ignored) { }
        return stub;
    }

    public List<BookingResponse> getMyBookings(String email) {
        User user = userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return bookingRepo.findByCustomerOrderByCreatedAtDesc(user)
                .stream().map(this::withRatingAndReviewInfo).toList();
    }

    // Attaches the assigned provider's REAL average rating (from actual
    // submitted reviews) and whether this specific booking already has a
    // review — so the frontend can (a) show a genuine rating instead of a
    // hardcoded placeholder, and (b) know which completed bookings still
    // need a mandatory customer review.
    private BookingResponse withRatingAndReviewInfo(Booking b) {
        BookingResponse r = BookingResponse.from(b);
        if (b.getProvider() != null) {
            double avg = reviewRepo.avgRatingByProvider(b.getProvider());
            long count = reviewRepo.countByProvider(b.getProvider());
            r.setProviderRating(count > 0 ? Math.round(avg * 10.0) / 10.0 : null);
            r.setProviderReviewCount((int) count);
        }
        r.setHasReview(reviewRepo.existsByBookingId(b.getId()));
        return r;
    }

    public List<BookingResponse> getProviderJobs(String email) {
        User provider = userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Provider not found"));
        return bookingRepo.findByProviderOrderByCreatedAtDesc(provider)
                .stream().map(BookingResponse::from).toList();
    }

    public BookingResponse updateStatus(Long id, String status, String email) {
        Booking booking = bookingRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found"));
        Booking.BookingStatus newStatus = Booking.BookingStatus.valueOf(status.toUpperCase());
        booking.setStatus(newStatus);
        Booking saved = bookingRepo.save(booking);

        // ── Status-change notifications ────────────────────────────
        switch (newStatus) {
            case CONFIRMED   -> notifService.onBookingConfirmed(saved);
            case EN_ROUTE    -> notifService.onProviderEnRoute(saved);
            case IN_PROGRESS -> notifService.onBookingStarted(saved);
            case COMPLETED   -> notifService.onBookingCompleted(saved);
            case CANCELLED   -> notifService.onBookingCancelled(saved, "PROVIDER");
            default          -> {}
        }
        return BookingResponse.from(saved);
    }

    /** Provider taps "Start Journey" / "Navigate" — this is the ONLY thing that should
     *  ever make a customer's live tracking map start showing movement. Before this
     *  call, the customer app must show a "waiting for your provider" state, not an
     *  animated marker — otherwise tracking looks fake/simulated instead of real. */
    public BookingResponse startJourney(Long id, String providerEmail) {
        Booking booking = bookingRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found"));
        User provider = userRepo.findByEmail(providerEmail)
                .orElseThrow(() -> new RuntimeException("Provider not found"));
        if (booking.getProvider() == null || !booking.getProvider().getId().equals(provider.getId()))
            throw new RuntimeException("This job isn't assigned to you");
        if (booking.getStatus() != Booking.BookingStatus.CONFIRMED)
            throw new RuntimeException("Job must be accepted/confirmed before you can start the journey");

        booking.setStatus(Booking.BookingStatus.EN_ROUTE);
        Booking saved = bookingRepo.save(booking);
        notifService.onProviderEnRoute(saved);
        return BookingResponse.from(saved);
    }

    /** Provider taps "Arrived / Start Job" once they reach the customer — stops the
     *  live tracking map (job in progress, nothing left to track) and starts the work stage. */
    public BookingResponse arrive(Long id, String providerEmail) {
        Booking booking = bookingRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found"));
        User provider = userRepo.findByEmail(providerEmail)
                .orElseThrow(() -> new RuntimeException("Provider not found"));
        if (booking.getProvider() == null || !booking.getProvider().getId().equals(provider.getId()))
            throw new RuntimeException("This job isn't assigned to you");
        if (booking.getStatus() != Booking.BookingStatus.EN_ROUTE)
            throw new RuntimeException("You need to start the journey before marking arrival");

        booking.setStatus(Booking.BookingStatus.IN_PROGRESS);
        Booking saved = bookingRepo.save(booking);
        notifService.onBookingStarted(saved);
        return BookingResponse.from(saved);
    }

    public BookingResponse cancel(Long id, String email) {
        User customer = userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Customer not found"));
        Booking booking = bookingRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        if (booking.getCustomer() == null || !booking.getCustomer().getId().equals(customer.getId()))
            throw new RuntimeException("This booking does not belong to you");
        if (booking.getStatus() == Booking.BookingStatus.CANCELLED)
            throw new RuntimeException("This booking is already cancelled");
        if (booking.getStatus() == Booking.BookingStatus.COMPLETED)
            throw new RuntimeException("Completed bookings can't be cancelled");
        if (booking.getStatus() == Booking.BookingStatus.IN_PROGRESS)
            throw new RuntimeException("This job is already in progress — contact support to cancel");

        booking.setStatus(Booking.BookingStatus.CANCELLED);
        booking.setPaymentStatus(Booking.PaymentStatus.REFUNDED);
        Booking saved = bookingRepo.save(booking);

        notifService.onBookingCancelled(saved, "CUSTOMER");
        notifService.onRefundIssued(saved);
        try { emailService.sendBookingCancelledEmail(saved, "CUSTOMER"); } catch (Exception ignored) {}

        return BookingResponse.from(saved);
    }

    /** Reschedule — customer-initiated, capped at 2 reschedules, only while not yet en-route/started */
    public BookingResponse reschedule(Long id, String newDate, String newSlot, String email) {
        User customer = userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Customer not found"));
        Booking booking = bookingRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        if (booking.getCustomer() == null || !booking.getCustomer().getId().equals(customer.getId()))
            throw new RuntimeException("This booking does not belong to you");
        if (booking.getStatus() == Booking.BookingStatus.CANCELLED || booking.getStatus() == Booking.BookingStatus.COMPLETED)
            throw new RuntimeException("This booking can no longer be rescheduled");
        if (booking.getStatus() == Booking.BookingStatus.EN_ROUTE || booking.getStatus() == Booking.BookingStatus.IN_PROGRESS)
            throw new RuntimeException("Your provider is already on the way — too late to reschedule");
        if (newSlot == null || newSlot.isBlank())
            throw new RuntimeException("Please choose a new time slot");

        int usedSoFar = booking.getRescheduleCount() != null ? booking.getRescheduleCount() : 0;
        if (usedSoFar >= 2)
            throw new RuntimeException("This booking has already been rescheduled twice — please cancel and rebook instead");

        String oldSlot = booking.getSlot();
        String combined = (newDate != null && !newDate.isBlank()) ? (newDate + " · " + newSlot) : newSlot;
        booking.setSlot(combined);
        booking.setRescheduleCount(usedSoFar + 1);
        Booking saved = bookingRepo.save(booking);

        notifService.onBookingRescheduled(saved, oldSlot);
        try { emailService.sendBookingRescheduledEmail(saved, oldSlot); } catch (Exception ignored) {}

        return BookingResponse.from(saved);
    }

    public BookingResponse accept(Long id, String providerEmail) {
        Booking booking = bookingRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found"));
        User provider = userRepo.findByEmail(providerEmail)
                .orElseThrow(() -> new RuntimeException("Provider not found"));
        if (booking.getProvider() != null && !booking.getProvider().getId().equals(provider.getId()))
            throw new RuntimeException("This job has already been accepted by another provider");
        if (booking.getStatus() == Booking.BookingStatus.CANCELLED)
            throw new RuntimeException("This booking was cancelled");
        booking.setProvider(provider);
        booking.setStatus(Booking.BookingStatus.CONFIRMED);
        Booking saved = bookingRepo.save(booking);

        notifService.onBookingConfirmed(saved);

        return BookingResponse.from(saved);
    }

    public BookingResponse complete(Long id, String providerEmail) {
        Booking booking = bookingRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Booking not found"));
        User provider = userRepo.findByEmail(providerEmail)
                .orElseThrow(() -> new RuntimeException("Provider not found"));
        if (booking.getProvider() == null || !booking.getProvider().getId().equals(provider.getId()))
            throw new RuntimeException("This job isn't assigned to you");

        booking.setStatus(Booking.BookingStatus.COMPLETED);

        // Cash on Delivery: the money changes hands right now, at job
        // completion — not at booking time. This is the moment (and the
        // ONLY moment) a COD booking should ever become "paid" and trigger
        // a payment notification/email.
        boolean codJustCollected = booking.getPaymentStatus() == Booking.PaymentStatus.PENDING
                && isCashMethod(booking.getPaymentMethod());
        if (codJustCollected) {
            booking.setPaymentStatus(Booking.PaymentStatus.PAID);
        }

        Booking saved = bookingRepo.save(booking);
        notifService.onBookingCompleted(saved);

        if (codJustCollected) {
            com.tasksphere.entity.Payment payment = savePaymentHistory(
                    saved, "COD" + saved.getId() + "_" + System.currentTimeMillis(), null, null);
            emailService.sendCustomerPaymentReceipt(saved);
            if (saved.getProvider() != null) {
                notifService.onPaymentReceived(payment);
                emailService.sendProviderEarningsReceipt(payment);
            }
        }

        return BookingResponse.from(saved);
    }
}
