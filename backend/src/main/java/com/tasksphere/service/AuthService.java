package com.tasksphere.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasksphere.dto.AuthDtos.*;
import com.tasksphere.entity.User;
import com.tasksphere.repository.UserRepository;
import com.tasksphere.security.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class AuthService {

    @Autowired private UserRepository       userRepo;
    @Autowired private PasswordEncoder      encoder;
    @Autowired private JwtUtils             jwtUtils;
    @Autowired private NotificationService  notifService;
    @Autowired private EmailService         emailService;

    @Value("${app.google-auth.enabled:false}")
    private boolean googleAuthEnabled;

    @Value("${google.oauth.client-id:YOUR_GOOGLE_OAUTH_CLIENT_ID.apps.googleusercontent.com}")
    private String googleClientId;

    private static final SecureRandom RANDOM = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthResponse register(RegisterRequest req) {
        if (userRepo.existsByEmail(req.getEmail())) {
            throw new RuntimeException("Email already registered");
        }
        User user = User.builder()
                .name(req.getName())
                .email(req.getEmail())
                .password(encoder.encode(req.getPassword()))
                .phone(req.getPhone())
                .role(req.getRole())
                .build();
        user = userRepo.save(user);

        // ── Notifications ──────────────────────────────────────────
        notifService.onWelcome(user);
        if (user.getRole() == User.Role.PROVIDER) {
            notifService.onNewProviderSignup(user);
        }

        return buildResponse(user);
    }

    public AuthResponse login(LoginRequest req) {
        User user = userRepo.findByEmail(req.getEmail())
                // Registration must happen before login — if no account exists
                // for this email, there is nothing to log into.
                .orElseThrow(() -> new RuntimeException("No account found for this email. Please register first."));
        if (!encoder.matches(req.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid email or password");
        }
        if (user.getStatus() == User.Status.SUSPENDED) {
            throw new RuntimeException("Account suspended. Contact support.");
        }
        // If the person picked a role on the login screen ("Login as Customer /
        // Provider / Admin"), it must match what they actually registered as.
        // A Customer account can't be used to log into the Provider or Admin
        // dashboard just because the password happens to match.
        if (req.getRole() != null && req.getRole() != user.getRole()) {
            throw new RuntimeException("This account is registered as " + user.getRole()
                    + ", not " + req.getRole() + ". Please choose the correct role, or register a new "
                    + req.getRole() + " account.");
        }
        return buildResponse(user);
    }

    // ── Google Sign-In ───────────────────────────────────────────────
    // Verifies the ID token Google Identity Services handed to the frontend,
    // then either logs the matching existing account in, or — if no account
    // exists yet for that Google email — registers a brand-new one (first
    // Google sign-in acts as the "register", exactly like the password flow).
    public AuthResponse googleLogin(GoogleLoginRequest req) {
        if (!googleAuthEnabled || googleClientId == null || googleClientId.startsWith("YOUR_")) {
            throw new RuntimeException("Google login is not configured on the server yet.");
        }

        JsonNode payload = verifyGoogleIdToken(req.getIdToken());

        String aud = payload.path("aud").asText("");
        if (!googleClientId.equals(aud)) {
            throw new RuntimeException("Google sign-in token was not issued for this app.");
        }
        if (!"true".equals(payload.path("email_verified").asText("false"))) {
            throw new RuntimeException("Your Google account's email is not verified.");
        }

        String email = payload.path("email").asText(null);
        String name  = payload.path("name").asText(null);
        if (email == null) {
            throw new RuntimeException("Could not read your email from Google.");
        }

        User user = userRepo.findByEmail(email).orElse(null);

        if (user == null) {
            // First-time Google sign-in == registration.
            User.Role role = req.getRole() != null ? req.getRole() : User.Role.CUSTOMER;
            user = User.builder()
                    .name(name != null ? name : email.split("@")[0])
                    .email(email)
                    // Google-authenticated accounts don't use a local password —
                    // store a random bcrypt hash so the column stays non-null and
                    // the (unreachable) password never happens to match anything.
                    .password(encoder.encode(RANDOM.nextLong() + "-google-" + System.nanoTime()))
                    .role(role)
                    .build();
            user = userRepo.save(user);
            notifService.onWelcome(user);
            if (user.getRole() == User.Role.PROVIDER) {
                notifService.onNewProviderSignup(user);
            }
        } else {
            if (user.getStatus() == User.Status.SUSPENDED) {
                throw new RuntimeException("Account suspended. Contact support.");
            }
            if (req.getRole() != null && req.getRole() != user.getRole()) {
                throw new RuntimeException("This account is registered as " + user.getRole()
                        + ", not " + req.getRole() + ". Please choose the correct role.");
            }
        }

        return buildResponse(user);
    }

    private JsonNode verifyGoogleIdToken(String idToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://oauth2.googleapis.com/tokeninfo?id_token=" + idToken))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Invalid or expired Google sign-in token.");
            }
            return mapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Could not verify Google sign-in right now. Please try again.");
        }
    }

    // ── Forgot password: generate + email a 6-digit OTP ─────────────
    public void forgotPassword(String email) {
        userRepo.findByEmail(email).ifPresent(user -> {
            String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
            user.setResetOtp(otp);
            user.setResetOtpExpiry(LocalDateTime.now().plusMinutes(10));
            userRepo.save(user);
            emailService.sendOtpEmail(user.getEmail(), user.getName(), otp, "reset your TaskSphere password");
        });
        // Always respond the same way whether or not the email exists — avoids leaking
        // which emails are registered. The controller returns a generic success message.
    }

    // ── Reset password using the emailed OTP ────────────────────────
    public void resetPassword(ResetPasswordRequest req) {
        User user = userRepo.findByEmail(req.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid or expired code"));

        if (user.getResetOtp() == null
                || !user.getResetOtp().equals(req.getOtp())
                || user.getResetOtpExpiry() == null
                || user.getResetOtpExpiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Invalid or expired code");
        }

        user.setPassword(encoder.encode(req.getNewPassword()));
        user.setResetOtp(null);
        user.setResetOtpExpiry(null);
        userRepo.save(user);
    }

    private AuthResponse buildResponse(User user) {
        String token = jwtUtils.generateToken(user.getEmail(), user.getRole().name());
        AuthResponse res = new AuthResponse();
        res.setToken(token);
        AuthResponse.UserInfo info = new AuthResponse.UserInfo();
        info.setId(user.getId());
        info.setName(user.getName());
        info.setEmail(user.getEmail());
        info.setPhone(user.getPhone());
        info.setRole(user.getRole().name());
        res.setUser(info);
        return res;
    }
}
