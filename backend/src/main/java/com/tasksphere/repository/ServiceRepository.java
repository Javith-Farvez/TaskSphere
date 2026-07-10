package com.tasksphere.repository;

import com.tasksphere.entity.Service;
import com.tasksphere.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ServiceRepository extends JpaRepository<Service, Long> {
    List<Service> findByProviderOrderByCreatedAtDesc(User provider);
    List<Service> findByProviderAndEnabledOrderByCreatedAtDesc(User provider, Boolean enabled);

    // ── Used by the AI Cost Estimator ───────────────────────────
    List<Service> findByCategoryIgnoreCaseAndEnabledTrue(String category);

    @org.springframework.data.jpa.repository.Query(
        "SELECT COALESCE(AVG(s.price), 0) FROM Service s WHERE LOWER(s.category) = LOWER(:category) AND s.enabled = true")
    Double avgPriceByCategory(String category);
}
