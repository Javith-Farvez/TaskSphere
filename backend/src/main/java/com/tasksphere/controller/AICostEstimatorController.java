package com.tasksphere.controller;

import com.tasksphere.dto.AIEstimateDtos.EstimateRequest;
import com.tasksphere.service.AICostEstimatorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AICostEstimatorController {

    @Autowired private AICostEstimatorService estimatorService;

    /** Core endpoint — called live from the booking flow (Step 2) on every
     *  service/provider/urgency/complexity change to refresh the price. */
    @PostMapping("/estimate")
    public ResponseEntity<?> estimate(@RequestBody EstimateRequest req,
                                       @AuthenticationPrincipal String email) {
        try {
            if (req.getServiceCategory() == null || req.getServiceCategory().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("message", "serviceCategory is required"));
            }
            return ResponseEntity.ok(estimatorService.estimate(req, email));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** Logged-in customer's past estimates ("My AI Estimates" panel). */
    @GetMapping("/history")
    public ResponseEntity<?> history(@AuthenticationPrincipal String email) {
        try {
            return ResponseEntity.ok(estimatorService.history(email));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
