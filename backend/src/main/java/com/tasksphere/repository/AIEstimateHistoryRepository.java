package com.tasksphere.repository;

import com.tasksphere.entity.AIEstimateHistory;
import com.tasksphere.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AIEstimateHistoryRepository extends JpaRepository<AIEstimateHistory, Long> {

    List<AIEstimateHistory> findByCustomerOrderByCreatedAtDesc(User customer);

    List<AIEstimateHistory> findTop20ByOrderByCreatedAtDesc();

    @Query("SELECT COUNT(e) FROM AIEstimateHistory e WHERE LOWER(e.serviceCategory) = LOWER(:category)")
    long countByCategory(String category);

    @Query("SELECT COALESCE(AVG(e.estimatedPrice), 0) FROM AIEstimateHistory e WHERE LOWER(e.serviceCategory) = LOWER(:category)")
    Double avgPriceByCategory(String category);

    @Query("SELECT COALESCE(AVG(e.estimatedDurationMinutes), 0) FROM AIEstimateHistory e WHERE LOWER(e.serviceCategory) = LOWER(:category)")
    Double avgDurationByCategory(String category);
}
