package com.tasksphere.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tasksphere.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Real Google Maps integration for geocoding, distance/ETA, and provider
 * matching — talking to Google's REST APIs directly (Geocoding API +
 * Distance Matrix API) over HTTPS, no extra SDK dependency needed.
 *
 * Feature-flagged via app.maps.enabled — until a real key is supplied,
 * geocode() returns null (caller falls back to whatever the customer typed)
 * and distanceAndEta() falls back to an honest Haversine straight-line
 * estimate instead of pretending to have live traffic data. Everything
 * upgrades automatically the moment app.maps.enabled=true + a real key are
 * set — zero code changes needed.
 */
@Service
public class GoogleMapsService {

    @Value("${app.maps.enabled:false}")
    private boolean mapsEnabled;

    @Value("${google.maps.api-key:YOUR_GOOGLE_MAPS_API_KEY}")
    private String apiKey;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public boolean isConfigured() {
        return mapsEnabled && apiKey != null && !apiKey.isBlank() && !apiKey.equals("YOUR_GOOGLE_MAPS_API_KEY");
    }

    /** Address → {lat, lng}. Returns null if Maps isn't configured or geocoding fails. */
    public Map<String, Double> geocode(String address) {
        if (!isConfigured() || address == null || address.isBlank()) return null;
        try {
            String encoded = URLEncoder.encode(address, StandardCharsets.UTF_8);
            String url = "https://maps.googleapis.com/maps/api/geocode/json?address=" + encoded + "&key=" + apiKey;
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());
            if (!"OK".equals(json.path("status").asText())) return null;
            JsonNode loc = json.path("results").get(0).path("geometry").path("location");
            return Map.of("lat", loc.path("lat").asDouble(), "lng", loc.path("lng").asDouble());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Distance (km) + ETA (minutes) between two points. Uses Google's
     * Distance Matrix API (real, live-traffic-aware) when configured;
     * otherwise falls back to Haversine straight-line distance + an assumed
     * 30 km/h average urban speed — an honest estimate, clearly labeled as
     * such in the response, never presented as live traffic data it isn't.
     */
    public Map<String, Object> distanceAndEta(double lat1, double lng1, double lat2, double lng2) {
        if (isConfigured()) {
            try {
                String origins = lat1 + "," + lng1;
                String dests = lat2 + "," + lng2;
                String url = "https://maps.googleapis.com/maps/api/distancematrix/json?origins=" + origins
                        + "&destinations=" + dests + "&key=" + apiKey;
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode json = mapper.readTree(response.body());
                JsonNode element = json.path("rows").get(0).path("elements").get(0);
                if ("OK".equals(element.path("status").asText())) {
                    double distanceKm = element.path("distance").path("value").asDouble() / 1000.0;
                    int etaMinutes = (int) Math.round(element.path("duration").path("value").asDouble() / 60.0);
                    return Map.of("distanceKm", round1(distanceKm), "etaMinutes", etaMinutes, "source", "google_live");
                }
            } catch (Exception ignored) {
                // fall through to Haversine estimate below
            }
        }
        double distanceKm = haversineKm(lat1, lng1, lat2, lng2);
        int etaMinutes = (int) Math.round((distanceKm / 30.0) * 60); // 30 km/h avg urban speed assumption
        return Map.of("distanceKm", round1(distanceKm), "etaMinutes", Math.max(etaMinutes, 3), "source", "estimated");
    }

    /**
     * Ranks a list of candidate providers by proximity to (lat, lng),
     * keeping only those who've shared live GPS. Returns distance-annotated
     * entries, nearest first — the core "Nearest Provider Algorithm".
     */
    public List<Map<String, Object>> rankByProximity(List<User> providers, double lat, double lng, int limit) {
        return providers.stream()
                .filter(p -> p.getCurrentLat() != null && p.getCurrentLng() != null)
                .map(p -> {
                    double d = haversineKm(lat, lng, p.getCurrentLat(), p.getCurrentLng());
                    return Map.<String, Object>of(
                            "provider", p,
                            "distanceKm", round1(d),
                            "etaMinutes", Math.max((int) Math.round((d / 30.0) * 60), 3)
                    );
                })
                .sorted(Comparator.comparingDouble(m -> (double) m.get("distanceKm")))
                .limit(limit)
                .toList();
    }

    public double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private double round1(double d) { return Math.round(d * 10.0) / 10.0; }
}
