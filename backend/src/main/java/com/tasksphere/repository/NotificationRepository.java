package com.tasksphere.repository;

import com.tasksphere.entity.Notification;
import com.tasksphere.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // ── Fetch notifications for a user ────────────────────────────
    List<Notification> findByUserOrderByCreatedAtDesc(User user);

    List<Notification> findByUserAndIsReadFalseOrderByCreatedAtDesc(User user);

    List<Notification> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    // ── Unread count ──────────────────────────────────────────────
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.user = :user AND n.isRead = false")
    long countUnreadByUser(@Param("user") User user);

    // ── Mark all read for a user ───────────────────────────────────
    @Modifying
    @Transactional
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = :now WHERE n.user = :user AND n.isRead = false")
    int markAllReadByUser(@Param("user") User user, @Param("now") LocalDateTime now);

    // ── Mark single notification read ──────────────────────────────
    @Modifying
    @Transactional
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = :now WHERE n.id = :id AND n.user = :user")
    int markOneRead(@Param("id") Long id, @Param("user") User user, @Param("now") LocalDateTime now);

    // ── Delete old read notifications (cleanup job) ────────────────
    @Modifying
    @Transactional
    @Query("DELETE FROM Notification n WHERE n.isRead = true AND n.createdAt < :cutoff")
    int deleteOldRead(@Param("cutoff") LocalDateTime cutoff);

    // ── Admin: all notifications ───────────────────────────────────
    @Query("SELECT n FROM Notification n WHERE n.type IN :types ORDER BY n.createdAt DESC")
    List<Notification> findByTypes(@Param("types") List<Notification.NotificationType> types, Pageable pageable);

    // ── Reference lookups ─────────────────────────────────────────
    List<Notification> findByReferenceIdAndReferenceType(Long referenceId, String referenceType);

    // ── Recent count for admin dashboard ──────────────────────────
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.createdAt >= :since")
    long countSince(@Param("since") LocalDateTime since);
}
