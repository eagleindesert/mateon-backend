package com.example.mateon.notification.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.notification.dto.NotificationResponseDTO;
import com.example.mateon.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(Authentication authentication) {
        return notificationService.subscribe(Long.valueOf(authentication.getName()));
    }
    // 내 알림 목록 조회
    @GetMapping
    public ApiResponse<List<NotificationResponseDTO>> getNotifications(Authentication authentication) {
        List<NotificationResponseDTO> response = notificationService.getMyNotifications(Long.valueOf(authentication.getName()));
        return ApiResponse.success(response);
    }
}