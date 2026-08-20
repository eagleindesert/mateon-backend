package com.example.mateon.matching.controller;

import com.example.mateon.common.dto.BaseResponse;
import com.example.mateon.matching.dto.request.MatchingIntentMessageRequestDTO;
import com.example.mateon.matching.dto.response.IntentSessionResponseDTO;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
import com.example.mateon.matching.service.MatchingIntentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 매칭 의도 추출 대화 API. 실제 추출/임베딩/문구 생성은 별도 FastAPI 서버가 한다.
 */
@Tag(name = "매칭 의도", description = "대화로 매칭 조건을 좁혀 나가는 세션. 추천의 전제 조건이다")
@RestController
@RequestMapping("/api/matching/intents")
@RequiredArgsConstructor
public class MatchingIntentController {

    private final MatchingIntentService matchingIntentService;

    /**
     * 사용자 답변을 보내고 AI 의 다음 질문(또는 완료 안내)을 받는다.
     * assistantMessage 를 그대로 화면에 보여주면 된다.
     */
    @Operation(summary = "의도 추출 대화 — 답변 보내고 다음 질문 받기",
      description = """
                          추천을 쓰려면 먼저 이 대화를 끝내야 한다. 사용자의 답변을 보내면 AI 의
                          다음 질문(또는 완료 안내)이 assistantMessage 로 오고, 그대로 화면에
                          보여주면 된다.

                          첫 호출이면 세션이 새로 만들어진다 — 별도의 "시작" API 는 없다.
                          완료 여부는 응답의 완료 플래그로 판단하고, 끝나야 추천 API 가
                          400 MATCHING_INTENT_REQUIRED 를 내지 않는다.""")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @ApiResponse(responseCode = "502",
      description = "AI_SERVER_ERROR — AI 서버 응답 처리에 실패했습니다.")
    @ApiResponse(responseCode = "503",
      description = "AI_SERVER_UNAVAILABLE — AI 서버에 연결할 수 없습니다.")
    @PostMapping("/messages")
    public ResponseEntity<BaseResponse<MatchingIntentResponseDTO>> submitMessage(
      @Valid @RequestBody MatchingIntentMessageRequestDTO request,
      Authentication authentication
    ) {
        Long userId = Long.valueOf(authentication.getName());
        MatchingIntentResponseDTO response = matchingIntentService.submitMessage(userId, request.getMessage());
        return ResponseEntity.ok(BaseResponse.success(response));
    }

    /**
     * 진행 중인 대화를 복원한다 (앱 재실행 등). AI 를 재호출하지 않는다.
     * 진행 중인 세션이 없으면 data 가 null 이다 — "아직 시작 안 함"은 정상 상태라 404 가 아니다.
     */
    @Operation(summary = "진행 중인 대화 복원",
      description = """
                          앱을 다시 켰을 때 대화를 이어 붙이는 용도다. AI 를 재호출하지 않으므로
                          가볍게 불러도 된다.

                          진행 중인 세션이 없으면 **data 가 null 이다** — "아직 시작 안 함"은 정상
                          상태라 404 가 아니다. 이때는 `POST /messages` 로 첫 답변을 보내면 된다.""")
    @GetMapping("/session")
    public ResponseEntity<BaseResponse<IntentSessionResponseDTO>> getCurrentSession(
      Authentication authentication
    ) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(BaseResponse.success(
          matchingIntentService.getCurrentSession(userId).orElse(null)));
    }

    /**
     * 진행 중인 대화를 버리고 처음부터 다시 시작한다. 새 세션은 다음 메시지 때 만들어진다.
     */
    @Operation(summary = "대화 처음부터 다시 시작",
      description = """
                          진행 중인 대화를 버린다. 새 세션은 다음 `POST /messages` 때 만들어지므로
                          이 호출만으로는 질문이 오지 않는다.

                          진행 중인 대화가 없어도 성공한다.""")
    @PostMapping("/session/restart")
    public ResponseEntity<BaseResponse<Void>> restart(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        matchingIntentService.restart(userId);
        return ResponseEntity.ok(BaseResponse.success(null));
    }
}
