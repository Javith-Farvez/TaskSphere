package com.tasksphere.repository;

import com.tasksphere.entity.FavoriteProvider;
import com.tasksphere.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface FavoriteProviderRepository extends JpaRepository<FavoriteProvider, Long> {
    boolean existsByCustomerAndProvider(User customer, User provider);
    Optional<FavoriteProvider> findByCustomerAndProvider(User customer, User provider);
    List<FavoriteProvider> findByCustomerOrderByCreatedAtDesc(User customer);
    long countByProvider(User provider);
    void deleteByCustomerAndProvider(User customer, User provider);
}
