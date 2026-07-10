package com.tasksphere.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Email of the admin/user who performed the action */
    @Column(nullable = false)
    private String actorEmail;

    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    /** e.g. "Category", "Complaint", "User", "Booking", "Provider" */
    private String entityType;

    private String entityId;

    @Column(length = 1000)
    private String details;

    private String ipAddress;

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum AuditAction {
        CREATE, UPDATE, DELETE, ENABLE, DISABLE, RESOLVE, LOGIN, EXPORT, APPROVE, REJECT, SUSPEND, OTHER
    }
}
