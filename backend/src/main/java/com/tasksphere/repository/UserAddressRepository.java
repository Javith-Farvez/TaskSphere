package com.tasksphere.repository;

import com.tasksphere.entity.User;
import com.tasksphere.entity.UserAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {
    List<UserAddress> findByCustomerOrderByIsDefaultDescCreatedAtDesc(User customer);
    Optional<UserAddress> findByCustomerAndIsDefaultTrue(User customer);
    long countByCustomer(User customer);
}
