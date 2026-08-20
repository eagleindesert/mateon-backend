package com.example.mateon.aigateway.controller;

import com.example.mateon.aigateway.dto.request.AiGatewayMessageRequestDTO;
import com.example.mateon.aigateway.dto.response.AiChatSessionDetailDTO;
import com.example.mateon.aigateway.dto.response.AiChatSessionSummaryDTO;
import com.example.mateon.aigateway.dto.response.AiGatewayResponseDTO;
import com.example.mateon.aigateway.service.AiGatewayService;
import com.example.mateon.common.dto.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 채팅의 단일 입구. 대화 세션을 관리하고, 도메인을 정해 해당 도메인 대화로 위임한다.
 */
@Tag(name = "AI 게이트웨이", description = "사용자 발화를 알맞은 AI 기능으로 보내는 입구. 챗봇은 여기로 시작한다")
@RestController
@RequestMapping("/api/ai/chat")
@RequiredArgsConstructor
public class AiGatewayController {

    private final AiGatewayService aiGatewayService;

    @Operation(summary = "새 대화 시작",
      description = """
                          빈 대화 세션을 만들고 그 id 를 돌려준다. 이후 발화는 이 `sessionId` 를 실어
                          `POST /api/ai/chat/messages` 로 보낸다.

                          제목(`title`)은 첫 발화가 들어올 때 서버가 채우므로 여기서는 null 이다.""")
    @ApiResponse(responseCode = "404", description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다.")
    @PostMapping("/sessions")
    public ResponseEntity<BaseResponse<AiChatSessionSummaryDTO>> createSession(
      Authentication authentication
    ) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(BaseResponse.success(aiGatewayService.createSession(userId)));
    }

    @Operation(summary = "내 대화 목록 (사이드바)",
      description = """
                          최근에 쓴 순으로 대화 세션을 돌려준다. 최대 50건이며 페이지네이션은 아직 없다.

                          `lastMessage` 는 미리보기용 마지막 한 줄이고, 아직 발화가 없는 대화 세션은
                          `title` 과 함께 null 이다.""")
    @GetMapping("/sessions")
    public ResponseEntity<BaseResponse<List<AiChatSessionSummaryDTO>>> listSessions(
      Authentication authentication
    ) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(BaseResponse.success(aiGatewayService.listSessions(userId)));
    }

    @Operation(summary = "대화 하나 복원",
      description = """
                          그 대화 세션의 대화를 시간순으로 전부 돌려준다. 그대로 채팅 화면에 그리면 된다.

                          도메인이 정해지기 전의 되묻기 턴도 함께 온다(그런 줄은 `domain` 이 null 이다)
                          — 사용자 눈에는 그것도 자기가 나눈 대화다.""")
    @ApiResponse(responseCode = "404",
      description = "AI_CHAT_SESSION_NOT_FOUND — 없는 대화이거나 내 대화가 아닙니다.")
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<BaseResponse<AiChatSessionDetailDTO>> getSession(
      @PathVariable Long sessionId,
      Authentication authentication
    ) {
        Long userId = Long.valueOf(authentication.getName());
        return ResponseEntity.ok(BaseResponse.success(aiGatewayService.getSession(userId, sessionId)));
    }

    @Operation(summary = "AI 채팅 — 발화 보내고 답변 받기",
      description = """
                          **챗봇 화면은 이 엔드포인트 하나로 시작한다.** 사용자가 무엇을 입력하든
                          여기로 보내면 서버가 어느 기능인지 판단해서 처리한다.

                          `sessionId` 는 필수다. 새 대화라면 먼저 `POST /api/ai/chat/sessions` 로
                          대화 세션을 만들고, 이어지는 턴은 직전 응답의 `sessionId` 를 그대로 보낸다.

                          응답의 `domain` 으로 분기하면 된다:
                          * `MATCHING_INTENT` — 매칭 의도 추출로 처리됐다. `matching` 에 해당 도메인의
                            응답이 그대로 담겨 오므로 **추가 호출이 필요 없다.** 이후 턴도 계속 여기로
                            보내면 되고, 대화가 끝났는지는 `matching.completed` 로 판단한다.
                          * `UNCLEAR` — 무엇을 원하는지 아직 모른다. `assistantMessage` 를 보여주고
                            사용자의 다음 발화를 다시 여기로 보낸다.
                          * `OUT_OF_SCOPE` — 서비스가 다루지 않는 주제다. `assistantMessage` 를 보여준다.

                          `assistantMessage` 는 **어떤 분기에서든 채워진다** — 분기를 따지기 전에
                          일단 그대로 화면에 그려도 된다.

                          `endpoint` 는 참고값이지 계약이 아니다. 이 URL 을 직접 호출하지 말고
                          `domain` 으로 분기할 것 — 경로는 바뀔 수 있다.

                          기존 `POST /api/matching/intents/messages` 도 그대로 동작하지만, 도메인
                          판정 없이 무조건 매칭으로 처리되고 대화 세션도 지정할 수 없으므로 신규 화면은
                          이쪽을 쓴다.""")
    @ApiResponse(responseCode = "404",
      description = "USER_NOT_FOUND — 사용자를 찾을 수 없습니다. / "
      + "AI_CHAT_SESSION_NOT_FOUND — 없는 대화이거나 내 대화가 아닙니다.")
    @ApiResponse(responseCode = "502",
      description = "AI_SERVER_ERROR — AI 서버 응답 처리에 실패했습니다. (위임된 도메인 AI 의 실패다. "
      + "라우터 자체가 실패하면 매칭으로 통과시키므로 이 코드가 나오지 않는다)")
    @ApiResponse(responseCode = "503",
      description = "AI_SERVER_UNAVAILABLE — AI 서버에 연결할 수 없습니다.")
    @PostMapping("/messages")
    public ResponseEntity<BaseResponse<AiGatewayResponseDTO>> submitMessage(
      @Valid @RequestBody AiGatewayMessageRequestDTO request,
      Authentication authentication
    ) {
        Long userId = Long.valueOf(authentication.getName());
        AiGatewayResponseDTO response
          = aiGatewayService.submitMessage(userId, request.getSessionId(), request.getMessage());
        return ResponseEntity.ok(BaseResponse.success(response));
    }
}
