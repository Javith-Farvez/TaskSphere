package com.tasksphere.repository;

import com.tasksphere.entity.Payment;
import com.tasksphere.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    List<Payment> findByProviderOrderByCreatedAtDesc(User provider);
    Optional<Payment> findByRazorpayRef(String ref);
    Optional<Payment> findByRazorpayOrderId(String orderId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.provider = :provider AND p.type = 'CREDIT' AND p.status = 'PAID'")
    Double totalReceivedByProvider(User provider);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.provider = :provider AND p.type = 'PAYOUT' AND p.status = 'PAID'")
    Double totalPaidOutByProvider(User provider);

    @Query("SELECT COUNT(p) FROM Payment p WHERE p.provider = :provider AND p.status = 'FAILED'")
    long countFailedByProvider(User provider);

    // ── Customer-facing payment history (via Booking.customer) ─────────
    @Query("SELECT p FROM Payment p WHERE p.booking.customer = :customer ORDER BY p.createdAt DESC")
    List<Payment> findByBookingCustomer(User customer);
}
