package com.tasksphere.dto;

import com.tasksphere.entity.User;
import jakarta.validation.constraints.*;
import lombok.Data;

public class AuthDtos {

    @Data
    public static class RegisterRequest {
        @NotBlank private String name;
        @Email @NotBlank private String email;
        @NotBlank @Size(min = 8) private String password;
        private String phone;
        @NotNull private User.Role role;
    }

    @Data
    public static class LoginRequest {
        @Email @NotBlank private String email;
        @NotBlank private String password;
        // Optional: role the user picked on the login screen ("Login as").
        // When present, it must match the account's actual stored role —
        // e.g. someone can't select "Provider" and log into a Customer
        // account (or an Admin account) just because the password matched.
        private User.Role role;
    }

    @Data
    public static class GoogleLoginRequest {
        // The Google ID token returned by Google Identity Services on the frontend.
        @NotBlank private String idToken;
        // Optional role picked on the login screen — enforced the same way as LoginRequest.
        private User.Role role;
    }

    @Data
    public static class ForgotPasswordRequest {
        @Email @NotBlank private String email;
    }

    @Data
    public static class ResetPasswordRequest {
        @Email @NotBlank private String email;
        @NotBlank private String otp;
        @NotBlank @Size(min = 8) private String newPassword;
    }

    @Data
    public static class AuthResponse {
        private String token;
        private UserInfo user;

        @Data
        public static class UserInfo {
            private Long id;
            private String name;
            private String email;
            private String phone;
            private String role;
        }
    }
}
