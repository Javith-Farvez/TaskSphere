package com.tasksphere.dto;

import lombok.Data;

public class ComplaintDtos {

    @Data
    public static class ComplaintRequest {
        private Long bookingId;
        private String subject;
        private String description;
        private String priority; // LOW, MEDIUM, HIGH, URGENT
    }

    @Data
    public static class ComplaintUpdateRequest {
        private String status;        // OPEN, IN_PROGRESS, RESOLVED, REJECTED
        private String adminResponse;
    }
}
