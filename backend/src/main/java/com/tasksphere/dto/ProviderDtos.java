package com.tasksphere.dto;

import com.tasksphere.entity.User;
import lombok.Data;

@Data
public class ProviderDtos {

    @Data
    public static class ProviderSummary {
        private Long id;
        private String name;
        private String phone;
        private String role;          // primary service category — derived from their first enabled service
        private Double avgRating;
        private Long reviewCount;
        private Long completedJobs;
        private Double fromPrice;     // cheapest enabled service price
        private Boolean favorited;    // only populated when requested by an authenticated customer

        public static ProviderSummary basic(User provider) {
            ProviderSummary s = new ProviderSummary();
            s.setId(provider.getId());
            s.setName(provider.getName());
            s.setPhone(provider.getPhone());
            return s;
        }
    }
}
