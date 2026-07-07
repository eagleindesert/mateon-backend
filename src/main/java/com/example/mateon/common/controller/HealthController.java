package com.example.mateon.common.controller;

import com.example.mateon.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/")
    public ResponseEntity<ApiResponse<Map<String, String>>> health() {
        Map<String, String> data = new HashMap<>();
        data.put("status", "UP");
        data.put("message", "Mateon Backend API is running");
        data.put("version", "1.0.0");
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, String>>> healthCheck() {
        Map<String, String> data = new HashMap<>();
        data.put("status", "UP");
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}

