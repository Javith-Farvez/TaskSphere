package com.tasksphere.controller;

import com.tasksphere.dto.AuthDtos.*;
import com.tasksphere.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired private AuthService authService;
    @Autowired private com.tasksphere.service.AuditLogService auditLogService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        try {
            return ResponseEntity.ok(authService.register(req));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        try {
            AuthResponse result = authService.login(req);
            if (result.getUser() != null && "ADMIN".equals(result.getUser().getRole())) {
                auditLogService.log(result.getUser().getEmail(), result.getUser().getName(),
                        com.tasksphere.entity.AuditLog.AuditAction.LOGIN, "Auth", null, "Admin logged in");
            }
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/google")
    public ResponseEntity<?> google(@Valid @RequestBody GoogleLoginRequest req) {
        try {
            AuthResponse result = authService.googleLogin(req);
            if (result.getUser() != null && "ADMIN".equals(result.getUser().getRole())) {
                auditLogService.log(result.getUser().getEmail(), result.getUser().getName(),
                        com.tasksphere.entity.AuditLog.AuditAction.LOGIN, "Auth", null, "Admin logged in via Google");
            }
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.status(401).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        authService.forgotPassword(req.getEmail());
        // Generic message regardless of whether the email is registered — avoids account enumeration.
        return ResponseEntity.ok(Map.of("message", "If an account exists for that email, a reset code has been sent."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        try {
            authService.resetPassword(req);
            return ResponseEntity.ok(Map.of("message", "Password reset successful. Please log in."));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
