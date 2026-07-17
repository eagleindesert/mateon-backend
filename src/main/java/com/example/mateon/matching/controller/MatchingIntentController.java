package com.example.mateon.matching.controller;

import com.example.mateon.common.dto.ApiResponse;
import com.example.mateon.matching.dto.request.MatchingIntentMessageRequestDTO;
import com.example.mateon.matching.dto.response.IntentSessionResponseDTO;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
import com.example.mateon.matching.service.MatchingIntentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 매칭 의도 추출 대화 API. 실제 추출/임베딩/문구 생성은 별도 FastAPI 서버가 한다.
 */
@RestController
@RequestMapping("/api/matching/intents")
@RequiredArgsConstructor
public class MatchingIntentController {

    private final MatchingIntentService matchingIntentService;

    /**
     * 사용자 답변을 보내고 AI 의 다음 질문(또는 완료 안내)을 받는다.
     * assistantMessage 를 그대로 화면에 보여주면 된다.
     */
    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<MatchingIntentResponseDTO>> submitMessage(
            @Valid @RequestBody MatchingIntentMessageRequestDTO request,
            Authentication authentication
    ) {
        Long userId = Long.valueOf(authentication.getName());
        MatchingIntentResponseDTO response = matchingIntentService.submitMessage(userId, request.getMessage());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 진행 중인 대화를 복원한다 (앱 재실행 등). AI 를 재호출하지 않는다.
     * 진행 중인 세션이 없으면 data 가 null 이다 — "아직 시작 안 함"은 정상 상태라 404 가 아니다.
     */
    @GetMapping("/session")
    public ResponseEntity<ApiResponse<IntentSessionResponseDTO>> getCurrentSession(
            Authentication authentication
    ) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(
                matchingIntentService.getCurrentSession(userId).orElse(null)));
    }

    /** 진행 중인 대화를 버리고 처음부터 다시 시작한다. 새 세션은 다음 메시지 때 만들어진다. */
    @PostMapping("/session/restart")
    public ResponseEntity<ApiResponse<Void>> restart(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        matchingIntentService.restart(userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
