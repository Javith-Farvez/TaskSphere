package com.tasksphere.repository;

import com.tasksphere.entity.Review;
import com.tasksphere.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findByProviderOrderByCreatedAtDesc(User provider);

    List<Review> findByCustomerOrderByCreatedAtDesc(User customer);

    java.util.Optional<Review> findByBookingId(Long bookingId);

    boolean existsByBookingId(Long bookingId);

    @Query("SELECT COALESCE(AVG(r.rating), 0) FROM Review r WHERE r.provider = :provider")
    Double avgRatingByProvider(User provider);

    @Query("SELECT COALESCE(AVG(r.rating), 0) FROM Review r")
    Double avgRatingOverall();

    @Query("SELECT COUNT(r) FROM Review r WHERE r.provider = :provider")
    long countByProvider(User provider);
}
