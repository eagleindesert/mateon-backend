package com.example.mateon.aigateway.controller;

import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aigateway.dto.response.AiGatewayResponseDTO;
import com.example.mateon.aigateway.service.AiGatewayService;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.dto.response.ExtractedDTO;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 챗봇 화면이 의존하는 응답 형태와 실패 코드를 고정한다.
 *
 * <p>프론트 계약에서 중요한 두 가지: 위임된 턴은 {@code matching} 이 통째로 실려 와서 <b>추가
 * 호출이 필요 없다</b>는 것과, {@code assistantMessage} 가 <b>어떤 분기에서든</b> 채워진다는 것.
 * 후자가 깨지면 화면에 빈 말풍선이 뜬다.
 *
 * <p>실패 코드는 재시도 정책을 가른다 — 503 은 잠시 후 다시, 502 는 다시 해도 소용없다.
 * 라우터 자체의 실패는 여기 오지 않는다(매칭으로 폴백하므로). 여기 오는 502/503 은 위임된
 * 도메인 AI 의 실패다.
 */
class AiGatewayControllerTest {

    private static final long USER_ID = 1L;

    private AiGatewayService aiGatewayService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        aiGatewayService = mock(AiGatewayService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AiGatewayController(aiGatewayService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("POST /api/ai/chat/messages")
    class SubmitMessage {

        @Test
        @DisplayName("위임된 턴은 matching 을 통째로 실어 준다 — 프론트가 다시 호출할 필요가 없다")
        void delegatedTurnCarriesDomainResponse() throws Exception {
            when(aiGatewayService.submitMessage(anyLong(), any())).thenReturn(
                    AiGatewayResponseDTO.delegated(100L, RoutableDomain.MATCHING_INTENT, matching()));

            perform("백엔드 팀 찾아요")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.domain").value("MATCHING_INTENT"))
                    .andExpect(jsonPath("$.data.endpoint").value("/api/matching/intents/messages"))
                    .andExpect(jsonPath("$.data.conversationId").value(100))
                    .andExpect(jsonPath("$.data.assistantMessage").value("어떤 기술을 쓰시나요?"))
                    .andExpect(jsonPath("$.data.matching.slotId").value(55))
                    .andExpect(jsonPath("$.data.matching.missingFields[0]").value("skills"));
        }

        @Test
        @DisplayName("범위 밖 턴은 matching 이 null 이고 endpoint 도 null 이다")
        void outOfScopeTurnHasNoDomainPayload() throws Exception {
            when(aiGatewayService.submitMessage(anyLong(), any())).thenReturn(
                    AiGatewayResponseDTO.handledByGateway(
                            100L, RoutableDomain.OUT_OF_SCOPE, "그 주제는 도와드리기 어려워요."));

            perform("오늘 서울 날씨 어때?")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.domain").value("OUT_OF_SCOPE"))
                    .andExpect(jsonPath("$.data.endpoint").doesNotExist())
                    .andExpect(jsonPath("$.data.matching").doesNotExist())
                    .andExpect(jsonPath("$.data.assistantMessage").value("그 주제는 도와드리기 어려워요."));
        }

        @Test
        @DisplayName("principal 문자열이 userId 로 변환되어 서비스로 간다")
        void principalBecomesUserId() throws Exception {
            when(aiGatewayService.submitMessage(anyLong(), any())).thenReturn(
                    AiGatewayResponseDTO.handledByGateway(100L, RoutableDomain.UNCLEAR, "무엇을 도와드릴까요?"));

            perform("안녕");

            verify(aiGatewayService).submitMessage(eq(USER_ID), eq("안녕"));
        }
    }

    @Nested
    @DisplayName("입력 검증 — AI 호출 비용이 붙기 전에 막는다")
    class Validation {

        @Test
        @DisplayName("빈 메시지는 400 이다")
        void blankMessageIs400() throws Exception {
            perform("   ").andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("1000자를 넘으면 400 이다")
        void tooLongMessageIs400() throws Exception {
            perform("가".repeat(1001)).andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("위임된 AI 의 장애 — 프론트 재시도 정책이 이 두 코드로 갈린다")
    class AiFailures {

        @Test
        @DisplayName("연결 불가는 503 이다 (잠시 후 재시도해도 된다)")
        void unavailableIs503() throws Exception {
            when(aiGatewayService.submitMessage(anyLong(), any()))
                    .thenThrow(new MateonException(ErrorCode.AI_SERVER_UNAVAILABLE));

            perform("백엔드 팀 찾아요")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(ErrorCode.AI_SERVER_UNAVAILABLE.getMessage()));
        }

        @Test
        @DisplayName("응답 처리 실패는 502 다 (다시 보내도 같은 결과다)")
        void serverErrorIs502() throws Exception {
            when(aiGatewayService.submitMessage(anyLong(), any()))
                    .thenThrow(new MateonException(ErrorCode.AI_SERVER_ERROR));

            perform("백엔드 팀 찾아요").andExpect(status().isBadGateway());
        }

        @Test
        @DisplayName("없는 사용자는 404 다")
        void unknownUserIs404() throws Exception {
            when(aiGatewayService.submitMessage(anyLong(), any()))
                    .thenThrow(new MateonException(ErrorCode.USER_NOT_FOUND));

            perform("백엔드 팀 찾아요").andExpect(status().isNotFound());
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions perform(String message) throws Exception {
        return mockMvc.perform(post("/api/ai/chat/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + message + "\"}")
                .principal(auth()));
    }

    private MatchingIntentResponseDTO matching() {
        return new MatchingIntentResponseDTO(10L, false, List.of("skills"),
                new ExtractedDTO(), "어떤 기술을 쓰시나요?", 55L);
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of());
    }
}
