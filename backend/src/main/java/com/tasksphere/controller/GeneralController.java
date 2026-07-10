package com.tasksphere.controller;

import com.tasksphere.dto.BookingDtos.*;
import com.tasksphere.entity.User;
import com.tasksphere.service.BookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class GeneralController {

    @Autowired private BookingService bookingService;
    @Autowired private com.tasksphere.repository.ReviewRepository reviewRepo;
    @Autowired private com.tasksphere.repository.UserRepository userRepo;
    @Autowired private com.tasksphere.service.ProviderSummaryService providerSummaryService;

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "TaskSphere API",
                "version", "1.0.0"
        ));
    }

    // Browse all providers (any authenticated role) — used by "Recommended For You" / "Browse all pros".
    // Includes a per-provider "favorited" flag when called by a logged-in customer.
    @GetMapping("/providers")
    public ResponseEntity<?> listProviders(@AuthenticationPrincipal String email) {
        User viewer = (email != null) ? userRepo.findByEmail(email).orElse(null) : null;
        var providers = userRepo.findAll().stream()
                .filter(u -> u.getRole() == User.Role.PROVIDER)
                .map(p -> providerSummaryService.build(p, viewer))
                .toList();
        return ResponseEntity.ok(providers);
    }

    /**
     * GET /api/providers/map  — public, no auth required.
     * Returns lat/lng of all ONLINE providers with a location fix
     * so the customer-side map can show live provider dots without
     * needing the user to be logged in.
     */
    @GetMapping("/providers/map")
    public ResponseEntity<?> providersMap() {
        return ResponseEntity.ok(
            userRepo.findByRoleAndStatus(com.tasksphere.entity.User.Role.PROVIDER, com.tasksphere.entity.User.Status.ACTIVE)
                .stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsOnline())
                          && p.getCurrentLat() != null
                          && p.getCurrentLng() != null)
                .map(p -> {
                    var m = new java.util.HashMap<String,Object>();
                    m.put("id",     p.getId());
                    m.put("name",   p.getName());
                    m.put("lat",    p.getCurrentLat());
                    m.put("lng",    p.getCurrentLng());
                    m.put("online", true);
                    m.put("updatedAt", p.getLocationUpdatedAt() != null ? p.getLocationUpdatedAt().toString() : "");
                    return m;
                }).toList()
        );
    }

    // (shown on provider profile / search results)
    @GetMapping("/providers/{id}/reviews")
    public ResponseEntity<?> providerReviews(@PathVariable Long id) {
        var provider = userRepo.findById(id).orElse(null);
        if (provider == null) return ResponseEntity.badRequest().body(Map.of("message", "Provider not found"));
        return ResponseEntity.ok(
            reviewRepo.findByProviderOrderByCreatedAtDesc(provider)
                .stream().map(com.tasksphere.dto.ReviewDtos.ReviewResponse::from).toList()
        );
    }

    // Shared bookings endpoint (used by frontend)
    @PostMapping("/bookings")
    public ResponseEntity<?> createBooking(@RequestBody CreateRequest req,
                                           @AuthenticationPrincipal String email) {
        try {
            return ResponseEntity.ok(bookingService.create(req, email));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
