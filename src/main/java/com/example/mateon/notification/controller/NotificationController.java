package com.example.mateon.notification.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.notification.dto.NotificationResponseDTO;
import com.example.mateon.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Tag(name = "알림", description = "알림 목록과 SSE 실시간 구독")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "알림 실시간 구독 (SSE)",
      description = """
                    **이 문서의 Try it out 으로는 확인할 수 없다.** 응답이 끝나지 않는
                    `text/event-stream` 연결이라 UI 가 계속 대기 상태로 남는다 —
                    브라우저 `EventSource` 나 RN 의 SSE 클라이언트로 붙어야 한다.

                    연결 직후 `connect` 이벤트가 한 번 오는데, 이건 연결 확인용이지 알림이 아니다.
                    이후 새 알림이 생길 때마다 이벤트가 밀려온다.

                    연결은 기본 1시간(`notification.sse-timeout`) 뒤 서버가 끊는다. 끊기면
                    다시 붙으면 되고, EventSource 는 알아서 재연결한다. 끊긴 동안 놓친 알림은
                    아래 목록 조회로 채운다.""")
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(Authentication authentication) {
        return notificationService.subscribe(Long.valueOf(authentication.getName()));
    }

    // 내 알림 목록 조회
    @Operation(summary = "내 알림 목록",
      description = """
                    지금까지 쌓인 알림을 내려준다. SSE 로 실시간 수신하더라도 앱을 켤 때는
                    이걸로 먼저 채워야 끊겨 있던 동안의 알림이 보인다.

                    알림이 없으면 빈 배열이다.""")
    @GetMapping
    public ApiResponse<List<NotificationResponseDTO>> getNotifications(Authentication authentication) {
        List<NotificationResponseDTO> response = notificationService.getMyNotifications(Long.valueOf(authentication.getName()));
        return ApiResponse.success(response);
    }
}
