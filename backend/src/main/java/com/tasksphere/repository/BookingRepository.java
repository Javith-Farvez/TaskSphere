package com.tasksphere.repository;

import com.tasksphere.entity.Booking;
import com.tasksphere.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByCustomerOrderByCreatedAtDesc(User customer);
    List<Booking> findByProviderOrderByCreatedAtDesc(User provider);
    List<Booking> findByProvider(User provider);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.provider = :provider AND b.status = 'COMPLETED'")
    long countCompletedByProvider(User provider);

    @Query("SELECT COALESCE(SUM(b.amount), 0) FROM Booking b WHERE b.provider = :provider AND b.status = 'COMPLETED'")
    Double sumEarningsByProvider(User provider);

    @Query("SELECT COUNT(b) FROM Booking b WHERE DATE(b.createdAt) = CURRENT_DATE")
    long countTodayBookings();

    @Query("SELECT COALESCE(SUM(b.amount), 0) FROM Booking b WHERE b.status = 'COMPLETED'")
    Double totalGMV();

    // ── Used by the AI Cost Estimator (real historical prices paid) ──
    @Query("SELECT b FROM Booking b WHERE LOWER(b.service) LIKE LOWER(CONCAT('%', :category, '%')) AND b.status = 'COMPLETED'")
    List<Booking> findCompletedByServiceCategoryLike(String category);

    @Query("SELECT COALESCE(AVG(b.amount), 0) FROM Booking b WHERE LOWER(b.service) LIKE LOWER(CONCAT('%', :category, '%')) AND b.status = 'COMPLETED'")
    Double avgAmountByServiceCategoryLike(String category);
}
