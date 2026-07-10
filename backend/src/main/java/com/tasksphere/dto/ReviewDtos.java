package com.tasksphere.dto;

import com.tasksphere.entity.Review;
import lombok.Data;
import java.time.LocalDateTime;

public class ReviewDtos {

    @Data
    public static class CreateRequest {
        private Integer rating;
        private String comment;
    }

    @Data
    public static class UpdateRequest {
        private Integer rating;
        private String comment;
    }

    @Data
    public static class ReplyRequest {
        private String reply;
    }

    @Data
    public static class ReviewResponse {
        private Long id;
        private Long bookingId;
        private String service;
        private Integer rating;
        private String comment;
        private String customerName;
        private String providerName;
        private String reply;
        private LocalDateTime repliedAt;
        private Boolean edited;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public static ReviewResponse from(Review r) {
            ReviewResponse out = new ReviewResponse();
            out.setId(r.getId());
            out.setRating(r.getRating());
            out.setComment(r.getComment());
            out.setReply(r.getReply());
            out.setRepliedAt(r.getRepliedAt());
            out.setEdited(Boolean.TRUE.equals(r.getEdited()));
            out.setCreatedAt(r.getCreatedAt());
            out.setUpdatedAt(r.getUpdatedAt());
            if (r.getBooking() != null) {
                out.setBookingId(r.getBooking().getId());
                out.setService(r.getBooking().getService());
            }
            if (r.getCustomer() != null) out.setCustomerName(r.getCustomer().getName());
            if (r.getProvider() != null) out.setProviderName(r.getProvider().getName());
            return out;
        }
    }
}
