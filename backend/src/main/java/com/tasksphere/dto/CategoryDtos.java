package com.tasksphere.dto;

import lombok.Data;

public class CategoryDtos {

    @Data
    public static class CategoryRequest {
        private String name;
        private String icon;
        private String description;
        private Boolean enabled;
        private Integer sortOrder;
    }
}
