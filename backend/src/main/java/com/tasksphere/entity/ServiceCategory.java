package com.tasksphere.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "service_categories")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ServiceCategory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(length = 10)
    private String icon;

    @Column(length = 500)
    private String description;

    @Builder.Default
    private Boolean enabled = true;

    @Builder.Default
    private Integer sortOrder = 0;

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PreUpdate
    public void preUpdate() { this.updatedAt = LocalDateTime.now(); }
}
