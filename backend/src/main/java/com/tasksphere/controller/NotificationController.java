package com.tasksphere.controller;

import com.tasksphere.dto.NotificationDtos.*;
import com.tasksphere.entity.User;
import com.tasksphere.repository.UserRepository;
import com.tasksphere.security.JwtUtils;
import com.tasksphere.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for notifications.
 *
 * Public Routes:
 *   GET  /api/notifications              → list (up to 50, newest first)
 *   GET  /api/notifications/summary      → unread count + top 10
 *   POST /api/notifications/read-all     → mark all as read
 *   POST /api/notifications/{id}/read    → mark one as read
 *   DELETE /api/notifications/{id}       → delete one
 *
 * Admin Routes:
 *   POST /api/admin/notifications/broadcast → send to all / role
 *   GET  /api/admin/notifications/stats     → platform notification stats
 */
@RestController
public class NotificationController {

    @Autowired private NotificationService notifService;
    @Autowired private UserRepository      userRepo;
    @Autowired private com.tasksphere.service.SseEmitterRegistry sseRegistry;
    @Autowired private com.tasksphere.security.JwtUtils jwtUtils;

    // ─────────────────────────────────────────────────────────────
    //  REAL-TIME STREAM  (Server-Sent Events)
    // ─────────────────────────────────────────────────────────────

    /**
     * GET /api/notifications/stream?token=JWT
     * Token passed as query param — EventSource cannot set Authorization header.
     */
    @GetMapping(value = "/api/notifications/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(
            @RequestParam(required = false) String token,
            @AuthenticationPrincipal String emailFromFilter) {
        String email = emailFromFilter;
        if ((email == null || email.isBlank()) && token != null && !token.isBlank() && !token.startsWith("demo")) {
            try { email = jwtUtils.getEmailFromToken(token); } catch (Exception ignored) { email = null; }
        }
        if (email == null || email.isBlank() || (token != null && token.startsWith("demo"))) {
            var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(30_000L);
            try { emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                    .name("connected").data(java.util.Map.of("status","demo"))); } catch (Exception ig) {}
            return emitter;
        }
        User user = userRepo.findByEmail(email).orElseThrow();
        return sseRegistry.register(user.getId());
    }

    // ─────────────────────────────────────────────────────────────
    //  USER-FACING ENDPOINTS  (any authenticated user)
    // ─────────────────────────────────────────────────────────────

    /** GET /api/notifications  —  full list (newest first, max 50) */
    @GetMapping("/api/notifications")
    public ResponseEntity<?> list(@AuthenticationPrincipal String email) {
        User user = getUser(email);
        List<NotificationResponse> list = notifService.getForUser(user);
        return ResponseEntity.ok(list);
    }

    /** GET /api/notifications/summary  —  unread count + top 10 */
    @GetMapping("/api/notifications/summary")
    public ResponseEntity<?> summary(@AuthenticationPrincipal String email) {
        User user = getUser(email);
        return ResponseEntity.ok(notifService.getSummary(user));
    }

    /** POST /api/notifications/read-all  —  mark all as read */
    @PostMapping("/api/notifications/read-all")
    public ResponseEntity<?> readAll(@AuthenticationPrincipal String email) {
        int updated = notifService.markAllRead(getUser(email));
        return ResponseEntity.ok(Map.of("marked", updated, "message", "All notifications marked as read"));
    }

    /** POST /api/notifications/{id}/read  —  mark single notification read */
    @PostMapping("/api/notifications/{id}/read")
    public ResponseEntity<?> readOne(@PathVariable Long id,
                                     @AuthenticationPrincipal String email) {
        boolean ok = notifService.markOneRead(id, getUser(email));
        if (!ok) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("id", id, "read", true));
    }

    /** DELETE /api/notifications/{id}  —  delete a notification */
    @DeleteMapping("/api/notifications/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal String email) {
        notifService.deleteOne(id, getUser(email));
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    // ─────────────────────────────────────────────────────────────
    //  ADMIN ENDPOINTS
    // ─────────────────────────────────────────────────────────────
    // NOTE: /api/admin/notifications/broadcast and /api/admin/notifications/stats
    // are implemented in AdminController (which owns the full "/api/admin" namespace
    // with real repo-backed stats). They were previously duplicated here, which
    // caused a Spring "Ambiguous mapping" startup crash. Do not re-add them here —
    // see AdminController#broadcast and AdminController#notifStats instead.

    // ─────────────────────────────────────────────────────────────
    //  INTERNAL TEST / DEV TRIGGER  (remove in production)
    // ─────────────────────────────────────────────────────────────

    /**
     * POST /api/notifications/test?type=BOOKING_PLACED
     * Fires a test notification to the authenticated user (dev only)
     */
    @PostMapping("/api/notifications/test")
    public ResponseEntity<?> testNotif(@RequestParam(defaultValue = "GENERAL") String type,
                                       @AuthenticationPrincipal String email) {
        User user = getUser(email);
        notifService.create(user,
            com.tasksphere.entity.Notification.NotificationType.GENERAL,
            "Test Notification 🔔",
            "This is a test notification of type: " + type,
            "🔔",
            com.tasksphere.entity.Notification.NotificationColor.BLUE,
            null, null);
        return ResponseEntity.ok(Map.of("sent", true, "to", email, "type", type));
    }

    // ─────────────────────────────────────────────────────────────
    //  HELPER
    // ─────────────────────────────────────────────────────────────
    private User getUser(String email) {
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));
    }
}
