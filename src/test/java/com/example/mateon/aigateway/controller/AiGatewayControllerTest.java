package com.example.mateon.aigateway.controller;

import com.example.mateon.aichat.domain.AiChatMessage;
import com.example.mateon.aichat.domain.AiChatRole;
import com.example.mateon.aichat.domain.AiChatSession;
import com.example.mateon.aichat.domain.AiDomainTask;
import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aichat.dto.AiChatSessionSummary;
import com.example.mateon.aigateway.dto.response.AiChatSessionDetailDTO;
import com.example.mateon.aigateway.dto.response.AiChatSessionSummaryDTO;
import com.example.mateon.aigateway.dto.response.AiGatewayResponseDTO;
import com.example.mateon.aigateway.service.AiGatewayService;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.dto.response.ExtractedDTO;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
import com.example.mateon.support.TestEntities;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 챗봇 화면이 의존하는 응답 형태와 실패 코드를 고정한다.
 *
 * <p>프론트 계약에서 중요한 세 가지: 위임된 턴은 {@code matching} 이 통째로 실려 와서 <b>추가
 * 호출이 필요 없다</b>는 것, {@code assistantMessage} 가 <b>어떤 분기에서든</b> 채워진다는 것,
 * 그리고 사이드바가 쓰는 목록·복원 응답의 모양이다. 두 번째가 깨지면 화면에 빈 말풍선이 뜬다.
 *
 * <p>실패 코드는 재시도 정책을 가른다 — 503 은 잠시 후 다시, 502 는 다시 해도 소용없다.
 * 라우터 자체의 실패는 여기 오지 않는다(매칭으로 폴백하므로). 여기 오는 502/503 은 위임된
 * 도메인 AI 의 실패다. 404 는 두 갈래인데(없는 사용자 / 남의 스레드) 프론트 입장에서는 같다.
 */
class AiGatewayControllerTest {

    private static final long USER_ID = 1L;
    private static final long SESSION_ID = 100L;

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
            when(aiGatewayService.submitMessage(anyLong(), anyLong(), any())).thenReturn(
                    AiGatewayResponseDTO.delegated(SESSION_ID, RoutableDomain.MATCHING_INTENT, matching()));

            perform("백엔드 팀 찾아요")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.domain").value("MATCHING_INTENT"))
                    .andExpect(jsonPath("$.data.endpoint").value("/api/matching/intents/messages"))
                    .andExpect(jsonPath("$.data.sessionId").value(100))
                    .andExpect(jsonPath("$.data.assistantMessage").value("어떤 기술을 쓰시나요?"))
                    .andExpect(jsonPath("$.data.matching.slotId").value(55))
                    .andExpect(jsonPath("$.data.matching.missingFields[0]").value("skills"));
        }

        @Test
        @DisplayName("범위 밖 턴은 matching 이 null 이고 endpoint 도 null 이다")
        void outOfScopeTurnHasNoDomainPayload() throws Exception {
            when(aiGatewayService.submitMessage(anyLong(), anyLong(), any())).thenReturn(
                    AiGatewayResponseDTO.handledByGateway(
                            SESSION_ID, RoutableDomain.OUT_OF_SCOPE, "그 주제는 도와드리기 어려워요."));

            perform("오늘 서울 날씨 어때?")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.domain").value("OUT_OF_SCOPE"))
                    .andExpect(jsonPath("$.data.endpoint").doesNotExist())
                    .andExpect(jsonPath("$.data.matching").doesNotExist())
                    .andExpect(jsonPath("$.data.assistantMessage").value("그 주제는 도와드리기 어려워요."));
        }

        @Test
        @DisplayName("principal 문자열이 userId 로, body 의 sessionId 가 그대로 서비스로 간다")
        void principalAndSessionIdReachTheService() throws Exception {
            when(aiGatewayService.submitMessage(anyLong(), anyLong(), any())).thenReturn(
                    AiGatewayResponseDTO.handledByGateway(
                            SESSION_ID, RoutableDomain.UNCLEAR, "무엇을 도와드릴까요?"));

            perform("안녕");

            verify(aiGatewayService).submitMessage(eq(USER_ID), eq(SESSION_ID), eq("안녕"));
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

        @Test
        @DisplayName("sessionId 를 빠뜨리면 400 이다 — 조용히 새 스레드를 만들지 않는다")
        void missingSessionIdIs400() throws Exception {
            mockMvc.perform(post("/api/ai/chat/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"안녕\"}")
                            .principal(auth()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("스레드 관리 — 사이드바가 쓰는 것들")
    class Sessions {

        @Test
        @DisplayName("새 스레드는 제목도 마지막 메시지도 비어 있다")
        void createReturnsAnEmptySession() throws Exception {
            when(aiGatewayService.createSession(USER_ID))
                    .thenReturn(AiChatSessionSummaryDTO.of(emptySession()));

            mockMvc.perform(post("/api/ai/chat/sessions").principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sessionId").value(100))
                    .andExpect(jsonPath("$.data.title").doesNotExist())
                    .andExpect(jsonPath("$.data.lastMessage").doesNotExist());
        }

        @Test
        @DisplayName("목록은 제목과 마지막 한 줄을 함께 준다 (사이드바에 그대로 그린다)")
        void listReturnsSummaries() throws Exception {
            when(aiGatewayService.listSessions(USER_ID)).thenReturn(List.of(
                    AiChatSessionSummaryDTO.of(new AiChatSessionSummary(
                            SESSION_ID, "백엔드 팀 찾아요", "어떤 기술을 쓰시나요?", LocalDateTime.now()))));

            mockMvc.perform(get("/api/ai/chat/sessions").principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].sessionId").value(100))
                    .andExpect(jsonPath("$.data[0].title").value("백엔드 팀 찾아요"))
                    .andExpect(jsonPath("$.data[0].lastMessage").value("어떤 기술을 쓰시나요?"));
        }

        @Test
        @DisplayName("복원은 게이트웨이 턴까지 시간순으로 준다 — 그런 줄은 domain 이 null 이다")
        void detailIncludesGatewayTurns() throws Exception {
            when(aiGatewayService.getSession(USER_ID, SESSION_ID))
                    .thenReturn(AiChatSessionDetailDTO.of(SESSION_ID, List.of(
                            gatewayMessage(1, "무엇을 도와드릴까요?"),
                            domainMessage(2, "백엔드 팀 찾아요"))));

            mockMvc.perform(get("/api/ai/chat/sessions/{id}", SESSION_ID).principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sessionId").value(100))
                    .andExpect(jsonPath("$.data.messages[0].message").value("무엇을 도와드릴까요?"))
                    .andExpect(jsonPath("$.data.messages[0].domain").doesNotExist())
                    .andExpect(jsonPath("$.data.messages[1].domain").value("MATCHING_INTENT"));
        }

        @Test
        @DisplayName("남의 스레드를 열면 404 다 (403 이면 존재 여부가 새어 나간다)")
        void otherUsersSessionIs404() throws Exception {
            when(aiGatewayService.getSession(anyLong(), anyLong()))
                    .thenThrow(new MateonException(ErrorCode.AI_CHAT_SESSION_NOT_FOUND));

            mockMvc.perform(get("/api/ai/chat/sessions/{id}", 999L).principal(auth()))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("위임된 AI 의 장애 — 프론트 재시도 정책이 이 두 코드로 갈린다")
    class AiFailures {

        @Test
        @DisplayName("연결 불가는 503 이다 (잠시 후 재시도해도 된다)")
        void unavailableIs503() throws Exception {
            when(aiGatewayService.submitMessage(anyLong(), anyLong(), any()))
                    .thenThrow(new MateonException(ErrorCode.AI_SERVER_UNAVAILABLE));

            perform("백엔드 팀 찾아요")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(ErrorCode.AI_SERVER_UNAVAILABLE.getMessage()));
        }

        @Test
        @DisplayName("응답 처리 실패는 502 다 (다시 보내도 같은 결과다)")
        void serverErrorIs502() throws Exception {
            when(aiGatewayService.submitMessage(anyLong(), anyLong(), any()))
                    .thenThrow(new MateonException(ErrorCode.AI_SERVER_ERROR));

            perform("백엔드 팀 찾아요").andExpect(status().isBadGateway());
        }

        @Test
        @DisplayName("없는 사용자는 404 다")
        void unknownUserIs404() throws Exception {
            when(aiGatewayService.submitMessage(anyLong(), anyLong(), any()))
                    .thenThrow(new MateonException(ErrorCode.USER_NOT_FOUND));

            perform("백엔드 팀 찾아요").andExpect(status().isNotFound());
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private ResultActions perform(String message) throws Exception {
        return mockMvc.perform(post("/api/ai/chat/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":" + SESSION_ID + ",\"message\":\"" + message + "\"}")
                .principal(auth()));
    }

    private MatchingIntentResponseDTO matching() {
        return new MatchingIntentResponseDTO(10L, false, List.of("skills"),
                new ExtractedDTO(), "어떤 기술을 쓰시나요?", 55L);
    }

    private AiChatSession emptySession() {
        return TestEntities.withId(new AiChatSession(user()), SESSION_ID);
    }

    private AiChatMessage gatewayMessage(int seq, String content) {
        return new AiChatMessage(emptySession(), seq, AiChatRole.ASSISTANT, content);
    }

    private AiChatMessage domainMessage(int seq, String content) {
        AiChatSession session = emptySession();
        AiChatMessage message = new AiChatMessage(session, seq, AiChatRole.USER, content);
        message.assignTask(TestEntities.withId(
                new AiDomainTask(session, user(), RoutableDomain.MATCHING_INTENT), 300L));
        return message;
    }

    private User user() {
        return User.builder().id(USER_ID).name("김학생").build();
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of());
    }
}
