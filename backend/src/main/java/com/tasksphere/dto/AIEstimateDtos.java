package com.tasksphere.dto;

import com.tasksphere.entity.AIEstimateHistory;
import lombok.Data;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class AIEstimateDtos {

    // ── Incoming request from the booking flow ──────────────────
    @Data
    public static class EstimateRequest {
        private String serviceCategory;     // required, e.g. "Plumbing"
        private String serviceType;         // optional, e.g. "Tap Repair"
        private String address;             // free-text address typed/selected by customer
        private Double customerLat;         // optional GPS
        private Double customerLng;
        private Double distanceKm;          // optional — if omitted, derived from nearest provider GPS or default
        private String urgency;             // NORMAL | URGENT | EMERGENCY
        private String complexity;          // LOW | MEDIUM | HIGH
    }

    // ── Price breakdown block ────────────────────────────────────
    @Data
    public static class PriceBreakdown {
        private Double basePrice;
        private Double distanceCharge;
        private Double urgencyCharge;
        private Double complexityCharge;
        private Double platformFee;
        private Double total;
    }

    // ── Provider reference block (recommended / nearest) ─────────
    @Data
    public static class ProviderRef {
        private Long id;
        private String name;
        private Double rating;
        private Double distanceKm;
    }

    // ── Full response returned to the frontend ───────────────────
    @Data
    public static class EstimateResponse {
        private Long estimateId;
        private String serviceCategory;
        private String serviceType;
        private Double estimatedPrice;
        private Integer estimatedDurationMinutes;
        private Double confidencePercent;
        private PriceBreakdown priceBreakdown;
        private ProviderRef recommendedProvider;
        private ProviderRef nearestProvider;
        private String generatedAt;

        public static EstimateResponse from(AIEstimateHistory h) {
            EstimateResponse r = new EstimateResponse();
            r.setEstimateId(h.getId());
            r.setServiceCategory(h.getServiceCategory());
            r.setServiceType(h.getServiceType());
            r.setEstimatedPrice(h.getEstimatedPrice());
            r.setEstimatedDurationMinutes(h.getEstimatedDurationMinutes());
            r.setConfidencePercent(h.getConfidencePercent());

            PriceBreakdown b = new PriceBreakdown();
            b.setBasePrice(h.getBasePrice());
            b.setDistanceCharge(h.getDistanceCharge());
            b.setUrgencyCharge(h.getUrgencyCharge());
            b.setComplexityCharge(h.getComplexityCharge());
            b.setPlatformFee(h.getPlatformFee());
            b.setTotal(h.getEstimatedPrice());
            r.setPriceBreakdown(b);

            if (h.getRecommendedProviderId() != null) {
                ProviderRef rp = new ProviderRef();
                rp.setId(h.getRecommendedProviderId());
                rp.setName(h.getRecommendedProviderName());
                rp.setRating(h.getRecommendedProviderRating());
                r.setRecommendedProvider(rp);
            }
            if (h.getNearestProviderId() != null) {
                ProviderRef np = new ProviderRef();
                np.setId(h.getNearestProviderId());
                np.setName(h.getNearestProviderName());
                np.setDistanceKm(h.getNearestProviderDistanceKm());
                r.setNearestProvider(np);
            }
            r.setGeneratedAt(h.getCreatedAt() != null
                ? h.getCreatedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"))
                : null);
            return r;
        }
    }

    // ── History list item (lighter weight) ────────────────────────
    @Data
    public static class HistoryItem {
        private Long id;
        private String serviceCategory;
        private String serviceType;
        private Double estimatedPrice;
        private Double confidencePercent;
        private String createdAt;

        public static HistoryItem from(AIEstimateHistory h) {
            HistoryItem i = new HistoryItem();
            i.setId(h.getId());
            i.setServiceCategory(h.getServiceCategory());
            i.setServiceType(h.getServiceType());
            i.setEstimatedPrice(h.getEstimatedPrice());
            i.setConfidencePercent(h.getConfidencePercent());
            i.setCreatedAt(h.getCreatedAt() != null
                ? h.getCreatedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"))
                : null);
            return i;
        }

        public static List<HistoryItem> fromList(List<AIEstimateHistory> list) {
            return list.stream().map(HistoryItem::from).toList();
        }
    }
}
