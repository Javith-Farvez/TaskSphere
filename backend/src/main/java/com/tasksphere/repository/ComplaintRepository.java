package com.tasksphere.repository;

import com.tasksphere.entity.Complaint;
import com.tasksphere.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ComplaintRepository extends JpaRepository<Complaint, Long> {
    List<Complaint> findByCustomerOrderByCreatedAtDesc(User customer);
    List<Complaint> findByStatusOrderByCreatedAtDesc(Complaint.ComplaintStatus status);

    @Query("SELECT COUNT(c) FROM Complaint c WHERE c.status = 'OPEN'")
    long countOpen();

    @Query("SELECT COUNT(c) FROM Complaint c WHERE c.status = 'IN_PROGRESS'")
    long countInProgress();

    @Query("SELECT COUNT(c) FROM Complaint c WHERE c.status = 'RESOLVED'")
    long countResolved();
}
