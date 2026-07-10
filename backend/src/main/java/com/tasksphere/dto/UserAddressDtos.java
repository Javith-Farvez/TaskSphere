package com.tasksphere.dto;

import com.tasksphere.entity.UserAddress;
import lombok.Data;
import java.time.format.DateTimeFormatter;

public class UserAddressDtos {

    @Data
    public static class AddressRequest {
        private String label;
        private String addressLine;
        private String city;
        private String state;
        private String pincode;
        private String landmark;
        private String phone;
        private Double lat;
        private Double lng;
        private Boolean isDefault;
    }

    @Data
    public static class AddressResponse {
        private Long id;
        private String label;
        private String addressLine;
        private String city;
        private String state;
        private String pincode;
        private String landmark;
        private String phone;
        private Double lat;
        private Double lng;
        private Boolean isDefault;
        private String createdAt;

        public static AddressResponse from(UserAddress a) {
            AddressResponse r = new AddressResponse();
            r.setId(a.getId());
            r.setLabel(a.getLabel());
            r.setAddressLine(a.getAddressLine());
            r.setCity(a.getCity());
            r.setState(a.getState());
            r.setPincode(a.getPincode());
            r.setLandmark(a.getLandmark());
            r.setPhone(a.getPhone());
            r.setLat(a.getLat());
            r.setLng(a.getLng());
            r.setIsDefault(a.getIsDefault());
            r.setCreatedAt(a.getCreatedAt() != null
                ? a.getCreatedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
                : null);
            return r;
        }
    }
}
