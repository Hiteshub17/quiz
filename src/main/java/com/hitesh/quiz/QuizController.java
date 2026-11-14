package com.hitesh.quiz;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles all HTTP endpoints for the quiz app.
 * For now, just a health-check endpoint for deployment platforms.
 */
@RestController
public class QuizController {

    /** Simple health check — returns plain text "ok" for Render or any load balancer. */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }
}
