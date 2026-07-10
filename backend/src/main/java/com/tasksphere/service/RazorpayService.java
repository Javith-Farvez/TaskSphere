package com.tasksphere.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/**
 * Real Razorpay integration — Order creation, HMAC-SHA256 payment-signature
 * verification, and Refunds — talking to Razorpay's REST API directly
 * (https://api.razorpay.com/v1/...) over HTTPS with Basic Auth, so no extra
 * SDK dependency is needed beyond the JDK's built-in HttpClient + Jackson
 * (already on the classpath via spring-boot-starter-web).
 *
 * Fully inert and clearly-erroring until real keys are supplied — flip
 * app.payments.enabled=true and fill in razorpay.key.id / razorpay.key.secret
 * in application.properties (get them from
 * https://dashboard.razorpay.com/app/keys) and every method below starts
 * working against the real Razorpay API with zero code changes.
 */
@Service
public class RazorpayService {

    @Value("${app.payments.enabled:false}")
    private boolean paymentsEnabled;

    @Value("${razorpay.key.id:YOUR_RAZORPAY_KEY_ID}")
    private String keyId;

    @Value("${razorpay.key.secret:YOUR_RAZORPAY_KEY_SECRET}")
    private String keySecret;

    @Value("${razorpay.webhook.secret:YOUR_RAZORPAY_WEBHOOK_SECRET}")
    private String webhookSecret;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String BASE_URL = "https://api.razorpay.com/v1";

    public boolean isConfigured() {
        return paymentsEnabled
                && keyId != null && !keyId.isBlank() && !keyId.equals("YOUR_RAZORPAY_KEY_ID")
                && keySecret != null && !keySecret.isBlank() && !keySecret.equals("YOUR_RAZORPAY_KEY_SECRET");
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                "Razorpay is not configured yet. Set app.payments.enabled=true and add your real " +
                "razorpay.key.id / razorpay.key.secret in application.properties (get them from " +
                "https://dashboard.razorpay.com/app/keys), then restart the server.");
        }
    }

    private String basicAuthHeader() {
        String cred = keyId + ":" + keySecret;
        return "Basic " + Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a real Razorpay Order — the correct/secure way to start a
     * checkout (vs. trusting a raw client-supplied amount). Amount is in
     * rupees; Razorpay's API wants paise.
     */
    public Map<String, Object> createOrder(double amountRupees, String receipt) {
        requireConfigured();
        try {
            long amountPaise = Math.round(amountRupees * 100);
            String body = mapper.writeValueAsString(Map.of(
                    "amount", amountPaise,
                    "currency", "INR",
                    "receipt", receipt,
                    "payment_capture", 1
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/orders"))
                    .header("Authorization", basicAuthHeader())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());
            if (response.statusCode() >= 400) {
                String msg = json.path("error").path("description").asText("Razorpay order creation failed");
                throw new RuntimeException(msg);
            }
            return Map.of(
                    "orderId", json.path("id").asText(),
                    "amount", amountRupees,
                    "amountPaise", amountPaise,
                    "currency", json.path("currency").asText("INR"),
                    "keyId", keyId
            );
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Could not reach Razorpay: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies a completed checkout is genuine by recomputing the HMAC-SHA256
     * signature Razorpay sent back, per their documented formula:
     *   expected = HMAC_SHA256(order_id + "|" + payment_id, key_secret)
     * and comparing it to what the client reported. This is the step the
     * codebase's TODO comment was missing — never trust a client-reported
     * "payment succeeded" without this check.
     */
    public boolean verifySignature(String orderId, String paymentId, String signature) {
        if (!isConfigured() || orderId == null || paymentId == null || signature == null) return false;
        try {
            String payload = orderId + "|" + paymentId;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(hash);
            return expected.equalsIgnoreCase(signature);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isWebhookConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank() && !webhookSecret.equals("YOUR_RAZORPAY_WEBHOOK_SECRET");
    }

    /**
     * Verifies an incoming Razorpay webhook is genuinely from Razorpay by
     * recomputing HMAC-SHA256(rawRequestBody, webhookSecret) and comparing
     * it to the X-Razorpay-Signature header, per Razorpay's documented
     * webhook verification formula. This — not the client-side checkout
     * handler — is the authoritative "Payment Success/Failure Callback":
     * server-to-server, can't be spoofed or skipped by closing the browser.
     */
    public boolean verifyWebhookSignature(String rawBody, String signatureHeader) {
        if (!isWebhookConfigured() || rawBody == null || signatureHeader == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(hash);
            return expected.equalsIgnoreCase(signatureHeader);
        } catch (Exception e) {
            return false;
        }
    }

    /** Full or partial refund of a captured payment. */
    public Map<String, Object> refund(String paymentId, Double amountRupees, String reason) {
        requireConfigured();
        try {
            Map<String, Object> payload = amountRupees != null
                    ? Map.of("amount", Math.round(amountRupees * 100), "notes", Map.of("reason", reason == null ? "" : reason))
                    : Map.of("notes", Map.of("reason", reason == null ? "" : reason));
            String body = mapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/payments/" + paymentId + "/refund"))
                    .header("Authorization", basicAuthHeader())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());
            if (response.statusCode() >= 400) {
                String msg = json.path("error").path("description").asText("Razorpay refund failed");
                throw new RuntimeException(msg);
            }
            return Map.of(
                    "refundId", json.path("id").asText(),
                    "status", json.path("status").asText("processed"),
                    "amount", json.path("amount").asInt(0) / 100.0
            );
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Could not reach Razorpay: " + e.getMessage(), e);
        }
    }
}
