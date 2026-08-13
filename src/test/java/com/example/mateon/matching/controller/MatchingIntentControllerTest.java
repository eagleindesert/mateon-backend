package com.example.mateon.matching.controller;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.domain.IntentMessageRole;
import com.example.mateon.matching.domain.IntentSessionStatus;
import com.example.mateon.matching.domain.MatchingIntentMessage;
import com.example.mateon.matching.dto.response.ExtractedDTO;
import com.example.mateon.matching.dto.response.IntentSessionResponseDTO;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
import com.example.mateon.matching.service.MatchingIntentService;
import com.example.mateon.support.TestEntities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 의도 추출 대화 API 의 응답 계약.
 *
 * <p>여기서 제일 중요한 건 <b>"진행 중인 세션 없음"이 404 가 아니라 {@code data: null} 인 200</b>
 * 이라는 점이다. 프론트는 앱을 켤 때마다 이 엔드포인트로 대화를 복원하는데, 아직 한 번도 시작하지
 * 않은 사용자가 절대다수다. 404 로 바꾸면 정상 상태가 에러 로그로 쏟아지고, 프론트는 404 를
 * catch 해서 무시하는 코드를 쓰게 된다 — 그러면 진짜 에러도 같이 삼켜진다.
 *
 * <p>두 번째는 <b>키 표기가 한 응답 안에서 일부러 섞여 있다</b>는 것이다. {@code missingFields}
 * 의 <i>값</i>은 AI 스펙과의 계약이라 snake_case 그대로 나가고({@code "desired_roles"}),
 * {@code extracted} 의 <i>키</i>는 우리 API 스키마라 camelCase 다({@code desiredRoles}).
 * 누군가 "일관성"을 이유로 한쪽에 맞추면 프론트의 진행률 표시가 조용히 빈다.
 *
 * <p>AI 서버 장애의 503/502 구분도 여기서 전선까지 확인한다 — 프론트 재시도 정책이 이 두 코드로
 * 갈린다 (503 은 잠시 후 재시도, 502 는 재시도해도 같다).
 */
class MatchingIntentControllerTest {

    private static final long USER_ID = 1L;
    private static final long SESSION_ID = 10L;

    private MatchingIntentService matchingIntentService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        matchingIntentService = mock(MatchingIntentService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new MatchingIntentController(matchingIntentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("POST /api/matching/intents/messages")
    class SubmitMessage {

        @Test
        @DisplayName("응답은 sessionId/completed/missingFields/extracted/assistantMessage/slotId 키를 가진다")
        void responseShape() throws Exception {
            when(matchingIntentService.submitMessage(USER_ID, "백엔드 팀 찾아요"))
                    .thenReturn(response(false, List.of("skills"), null));

            mockMvc.perform(post("/api/matching/intents/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"백엔드 팀 찾아요\"}")
                            .principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.sessionId").value(10))
                    .andExpect(jsonPath("$.data.completed").value(false))
                    .andExpect(jsonPath("$.data.missingFields[0]").value("skills"))
                    .andExpect(jsonPath("$.data.assistantMessage").value("어떤 기술을 쓰시나요?"))
                    .andExpect(jsonPath("$.data.slotId").doesNotExist());
        }

        @Test
        @DisplayName("완료되면 slotId 가 채워진다 (추천 호출의 전제 조건)")
        void completedCarriesSlotId() throws Exception {
            when(matchingIntentService.submitMessage(anyLong(), any()))
                    .thenReturn(response(true, List.of(), 55L));

            mockMvc.perform(post("/api/matching/intents/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"Spring 씁니다\"}")
                            .principal(auth()))
                    .andExpect(jsonPath("$.data.completed").value(true))
                    .andExpect(jsonPath("$.data.missingFields").isEmpty())
                    .andExpect(jsonPath("$.data.slotId").value(55));
        }

        @Test
        @DisplayName("missingFields 는 snake_case, extracted 키는 camelCase 다 (일부러 섞여 있다)")
        void mixedNamingIsIntentional() throws Exception {
            ExtractedDTO extracted = new ExtractedDTO();
            extracted.setDesiredRoles(List.of("백엔드"));
            extracted.setActivityGoal("수상");
            extracted.setExperienceLevel("입문");
            when(matchingIntentService.submitMessage(anyLong(), any()))
                    .thenReturn(new MatchingIntentResponseDTO(SESSION_ID, false,
                            List.of("desired_roles", "activity_style"), extracted, "문구", null));

            mockMvc.perform(post("/api/matching/intents/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"안녕\"}")
                            .principal(auth()))
                    // 값은 AI 스펙 그대로
                    .andExpect(jsonPath("$.data.missingFields[0]").value("desired_roles"))
                    .andExpect(jsonPath("$.data.missingFields[1]").value("activity_style"))
                    // 키는 우리 API 스키마
                    .andExpect(jsonPath("$.data.extracted.desiredRoles[0]").value("백엔드"))
                    .andExpect(jsonPath("$.data.extracted.activityGoal").value("수상"))
                    .andExpect(jsonPath("$.data.extracted.experienceLevel").value("입문"));
        }

        @Test
        @DisplayName("1536개 float 임베딩은 응답에 실리지 않는다 (프론트에 20KB 를 흘릴 이유가 없다)")
        void embeddingIsNotExposed() throws Exception {
            when(matchingIntentService.submitMessage(anyLong(), any()))
                    .thenReturn(response(true, List.of(), 55L));

            mockMvc.perform(post("/api/matching/intents/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"안녕\"}")
                            .principal(auth()))
                    .andExpect(jsonPath("$.data.embeddingVector").doesNotExist())
                    .andExpect(jsonPath("$.data.embeddingText").doesNotExist());
        }

        @Test
        @DisplayName("빈 메시지는 서비스까지 가지 않고 400 이다")
        void blankMessageIs400() throws Exception {
            mockMvc.perform(post("/api/matching/intents/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"   \"}")
                            .principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("입력값 검증에 실패했습니다."))
                    .andExpect(jsonPath("$.data.message").value("메시지는 비어 있을 수 없습니다."));

            verify(matchingIntentService, never()).submitMessage(anyLong(), any());
        }

        @Test
        @DisplayName("1000자를 넘으면 400 이다 (AI 프롬프트 비용 상한)")
        void tooLongMessageIs400() throws Exception {
            String tooLong = "가".repeat(1001);

            mockMvc.perform(post("/api/matching/intents/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"" + tooLong + "\"}")
                            .principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.message").value("메시지는 1000자를 넘을 수 없습니다."));

            verify(matchingIntentService, never()).submitMessage(anyLong(), any());
        }

        @Test
        @DisplayName("정확히 1000자는 통과한다 (경계)")
        void exactly1000IsAccepted() throws Exception {
            when(matchingIntentService.submitMessage(anyLong(), any()))
                    .thenReturn(response(false, List.of("skills"), null));

            mockMvc.perform(post("/api/matching/intents/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"" + "가".repeat(1000) + "\"}")
                            .principal(auth()))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("AI 서버 장애 — 프론트 재시도 정책이 이 두 코드로 갈린다")
    class AiFailures {

        @Test
        @DisplayName("연결 불가는 503 이다 (잠시 후 재시도해도 된다)")
        void unavailableIs503() throws Exception {
            when(matchingIntentService.submitMessage(anyLong(), any()))
                    .thenThrow(new MateonException(ErrorCode.AI_SERVER_UNAVAILABLE));

            mockMvc.perform(post("/api/matching/intents/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"안녕\"}")
                            .principal(auth()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(ErrorCode.AI_SERVER_UNAVAILABLE.getMessage()));
        }

        @Test
        @DisplayName("응답 처리 실패는 502 다 (재시도해도 같은 결과다)")
        void badResponseIs502() throws Exception {
            when(matchingIntentService.submitMessage(anyLong(), any()))
                    .thenThrow(new MateonException(ErrorCode.AI_SERVER_ERROR));

            mockMvc.perform(post("/api/matching/intents/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"안녕\"}")
                            .principal(auth()))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.message").value(ErrorCode.AI_SERVER_ERROR.getMessage()));
        }
    }

    @Nested
    @DisplayName("GET /api/matching/intents/session")
    class CurrentSession {

        @Test
        @DisplayName("진행 중인 세션이 없어도 200 이고 data 가 null 이다 — 404 로 바꾸지 말 것")
        void noSessionIsNot404() throws Exception {
            when(matchingIntentService.getCurrentSession(USER_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/matching/intents/session").principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    // 키가 사라지는 것도 아니고 빈 객체도 아니다. 정확히 null 이 전선을 넘는다.
                    .andExpect(content().string(containsString("\"data\":null")));
        }

        @Test
        @DisplayName("세션이 있으면 상태·대화 이력까지 함께 내려간다 (AI 재호출 없이 복원)")
        void restoresConversation() throws Exception {
            when(matchingIntentService.getCurrentSession(USER_ID)).thenReturn(Optional.of(session()));

            mockMvc.perform(get("/api/matching/intents/session").principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sessionId").value(10))
                    .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.data.completed").value(false))
                    .andExpect(jsonPath("$.data.missingFields[0]").value("skills"))
                    .andExpect(jsonPath("$.data.messages[0].role").value("USER"))
                    .andExpect(jsonPath("$.data.messages[0].message").value("백엔드 팀 찾아요"))
                    .andExpect(jsonPath("$.data.messages[0].createdAt").exists())
                    .andExpect(jsonPath("$.data.messages[1].role").value("ASSISTANT"));
        }
    }

    @Nested
    @DisplayName("POST /api/matching/intents/session/restart")
    class Restart {

        @Test
        @DisplayName("돌려줄 데이터가 없다 (새 세션은 다음 메시지 때 만들어진다)")
        void returnsNoData() throws Exception {
            mockMvc.perform(post("/api/matching/intents/session/restart").principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").doesNotExist());

            verify(matchingIntentService).restart(USER_ID);
        }
    }

    @Test
    @DisplayName("principal 문자열은 Long userId 로 변환돼 넘어간다 (세 엔드포인트 모두)")
    void principalBecomesLongUserId() throws Exception {
        when(matchingIntentService.submitMessage(anyLong(), any()))
                .thenReturn(response(false, List.of("skills"), null));
        when(matchingIntentService.getCurrentSession(anyLong())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/matching/intents/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"안녕\"}")
                .principal(auth()));
        mockMvc.perform(get("/api/matching/intents/session").principal(auth()));
        mockMvc.perform(post("/api/matching/intents/session/restart").principal(auth()));

        verify(matchingIntentService).submitMessage(USER_ID, "안녕");
        verify(matchingIntentService).getCurrentSession(USER_ID);
        verify(matchingIntentService).restart(USER_ID);
    }

    // --- 픽스처 -------------------------------------------------------------

    private MatchingIntentResponseDTO response(boolean completed, List<String> missingFields, Long slotId) {
        return new MatchingIntentResponseDTO(SESSION_ID, completed, missingFields,
                new ExtractedDTO(), "어떤 기술을 쓰시나요?", slotId);
    }

    private IntentSessionResponseDTO session() {
        return new IntentSessionResponseDTO(SESSION_ID, IntentSessionStatus.IN_PROGRESS, false,
                List.of("skills"), new ExtractedDTO(),
                List.of(new IntentSessionResponseDTO.MessageDTO(
                                message(1, IntentMessageRole.USER, "백엔드 팀 찾아요")),
                        new IntentSessionResponseDTO.MessageDTO(
                                message(2, IntentMessageRole.ASSISTANT, "어떤 기술을 쓰시나요?"))));
    }

    /** createdAt 은 {@code @CreatedDate} 라 감사 없이는 null 이다 — 직접 채워야 키가 나간다. */
    private MatchingIntentMessage message(int seq, IntentMessageRole role, String text) {
        MatchingIntentMessage message = new MatchingIntentMessage(null, seq, role, text);
        return TestEntities.withField(message, "createdAt", LocalDateTime.now());
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of());
    }
}
