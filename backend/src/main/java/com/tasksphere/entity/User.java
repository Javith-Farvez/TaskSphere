package com.tasksphere.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    private String phone;

    // ── Password reset OTP (email-based) ────────────────────────
    private String resetOtp;
    private LocalDateTime resetOtpExpiry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    // ── Live GPS tracking (provider) ────────────────────────────
    private Double currentLat;
    private Double currentLng;
    private LocalDateTime locationUpdatedAt;
    @Builder.Default
    private Boolean isOnline = false;

    // ── Working Hours (provider) ────────────────────────────────
    @Column(length = 20)
    private String workingDays;     // comma-separated: "MON,TUE,WED,THU,FRI"
    private String workStartTime;   // "09:00"
    private String workEndTime;     // "18:00"
    @Builder.Default
    private Integer maxJobsPerDay = 3;

    // ── Vacation Mode (provider) ────────────────────────────────
    @Builder.Default
    private Boolean vacationMode = false;
    private java.time.LocalDate vacationStart;
    private java.time.LocalDate vacationEnd;
    private String vacationReason;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum Role {
        CUSTOMER, PROVIDER, ADMIN
    }

    public enum Status {
        ACTIVE, INACTIVE, SUSPENDED
    }
}
