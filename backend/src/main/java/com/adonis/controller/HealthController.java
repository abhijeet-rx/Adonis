package com.adonis.controller;

import com.adonis.dto.HealthResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @Value("${spring.application.name:adonis-backend}")
    private String serviceName;

    @Value("${adonis.build.commit:${GIT_COMMIT:unknown}}")
    private String commit;

    @GetMapping
    public ResponseEntity<HealthResponse> checkHealth() {
        return ResponseEntity.ok(HealthResponse.ok(serviceName, "0.0.1-SNAPSHOT", commit));
    }
}
