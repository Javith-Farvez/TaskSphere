package com.tasksphere.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "provider_media")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ProviderMedia {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id", nullable = false)
    private User provider;

    @Column(nullable = false, length = 1000)
    private String cloudinaryUrl;

    private String publicId;     // Cloudinary public_id for deletion

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaType type;

    @Builder.Default
    private Boolean verified = false;  // for KYC docs

    private String documentType;       // AADHAAR, PAN, LICENCE (for KYC)
    private String certificateName;    // e.g. "Plumbing License", "Electrician Certificate"
    private String issuer;             // issuing authority / institute
    private String expiryDate;         // optional expiry (yyyy-MM-dd)

    @Column(updatable = false)
    @Builder.Default
    private LocalDateTime uploadedAt = LocalDateTime.now();

    public enum MediaType {
        PROFILE_PHOTO,
        SERVICE_IMAGE,
        ID_PROOF,
        CERTIFICATE,
        BEFORE_PHOTO,
        AFTER_PHOTO
    }
}
