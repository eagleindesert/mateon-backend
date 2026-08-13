package com.example.mateon.common.controller;

import com.example.mateon.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "헬스체크", description = "서버 생존 확인")
@RestController
public class HealthController {

    @Operation(summary = "루트 — 서버 생존 확인",
            description = "서버가 떠 있으면 status·message·version 을 준다. 토큰이 필요 없다.")
    @SecurityRequirement(name = "")  // 비로그인 허용
    @GetMapping("/")
    public ResponseEntity<ApiResponse<Map<String, String>>> health() {
        Map<String, String> data = new HashMap<>();
        data.put("status", "UP");
        data.put("message", "Mateon Backend API is running");
        data.put("version", "1.0.0");
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @Operation(summary = "헬스체크",
            description = "로드밸런서·배포 스크립트가 쓰는 경로. status 만 준다. 토큰이 필요 없다.")
    @SecurityRequirement(name = "")  // 비로그인 허용
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, String>>> healthCheck() {
        Map<String, String> data = new HashMap<>();
        data.put("status", "UP");
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}

