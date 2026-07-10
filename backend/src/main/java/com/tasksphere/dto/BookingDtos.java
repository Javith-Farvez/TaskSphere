package com.tasksphere.dto;

import com.tasksphere.entity.Booking;
import lombok.Data;
import java.time.LocalDateTime;

public class BookingDtos {

    @Data
    public static class CreateRequest {
        private String service;
        private String address;
        private String notes;
        private String slot;
        private String date;
        private Double amount;
        private String provider;
        private String paymentMethod;
        // Populated when payment went through real Razorpay checkout —
        // if present, the backend verifies the signature before marking paid.
        private String razorpayOrderId;
        private String razorpayPaymentId;
        private String razorpaySignature;
    }

    @Data
    public static class RescheduleRequest {
        private String date;   // e.g. "2026-07-04"
        private String slot;   // e.g. "3:00 PM"
    }

    @Data
    public static class BookingResponse {
        private Long id;
        private String service;
        private String address;
        private String slot;
        private Double amount;
        private String status;
        private String paymentStatus;
        private String customerName;
        private String providerName;
        private Long providerId;
        private String providerPhone;
        private String customerPhone;
        private String paymentRef;
        private Integer rescheduleCount;
        private LocalDateTime createdAt;
        // Provider's REAL accumulated rating (average of actual submitted
        // reviews for that provider) — never a hardcoded placeholder like
        // "4.9". Null/0 with reviewCount 0 means no reviews yet, which the
        // frontend should show honestly (e.g. "New Provider") instead of
        // making up a number.
        private Double providerRating;
        private Integer providerReviewCount;
        // Whether THIS booking already has a customer review attached —
        // drives the "rating is mandatory once completed" flow so a
        // completed job without a review can be flagged for a prompt.
        private Boolean hasReview;

        public static BookingResponse from(Booking b) {
            BookingResponse r = new BookingResponse();
            r.setId(b.getId());
            r.setService(b.getService());
            r.setAddress(b.getAddress());
            r.setSlot(b.getSlot());
            r.setAmount(b.getAmount());
            r.setStatus(b.getStatus().name());
            r.setPaymentStatus(b.getPaymentStatus().name());
            r.setCreatedAt(b.getCreatedAt());
            r.setRescheduleCount(b.getRescheduleCount() != null ? b.getRescheduleCount() : 0);
            if (b.getCustomer() != null) {
                r.setCustomerName(b.getCustomer().getName());
                r.setCustomerPhone(b.getCustomer().getPhone());
            }
            if (b.getProvider() != null) {
                r.setProviderName(b.getProvider().getName());
                r.setProviderId(b.getProvider().getId());
                r.setProviderPhone(b.getProvider().getPhone());
            }
            r.setPaymentRef(b.getPaymentRef());
            return r;
        }
    }
}
