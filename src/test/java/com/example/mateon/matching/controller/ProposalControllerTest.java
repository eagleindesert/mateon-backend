package com.example.mateon.matching.controller;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.dto.response.ProposalDraftResponseDTO;
import com.example.mateon.matching.service.ProposalAssemblyService;
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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 제안 문구 초안 API.
 *
 * <p><b>여기는 아무것도 저장하지 않는다.</b> 그래서 응답에 id 가 없고, 응답 상태도 201 이 아니라
 * 200 이다 (POST 인 건 LLM 호출을 유발하기 때문이지 생성이기 때문이 아니다). 누군가 "POST 니까
 * 201" 이라고 고치면 프론트가 초안 화면으로 넘어가지 못한다.
 *
 * <p>두 방향이 <b>같은 모양의 응답</b>을 내는 것도 계약이다 — 프론트는 초안 편집 화면 하나로
 * 양쪽을 처리하고 {@code direction} 으로만 문구 안내를 바꾼다. 그 {@code direction} 은 AI 응답이
 * 아니라 호출된 엔드포인트가 출처이므로, 여기서 전선까지 확인해 둘 가치가 있다.
 *
 * <p>인자 순서도 고정한다. 역제안은 {@code (teamId, 대상 userId, 요청자 팀장)} 인데 세 개가 전부
 * {@code Long} 이라 자리를 바꿔도 컴파일된다. 바뀌면 "팀 100 의 팀장인가" 검사가 엉뚱한 팀을
 * 보게 되고, 화면에는 멀쩡한 문장이 뜬다.
 */
class ProposalControllerTest {

    private static final long USER_ID = 1L;
    private static final long TEAM_ID = 100L;
    private static final long TARGET_USER_ID = 2L;

    private ProposalAssemblyService proposalAssemblyService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        proposalAssemblyService = mock(ProposalAssemblyService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProposalController(proposalAssemblyService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("POST /user-to-team (지원 문구)")
    class DraftForTeam {

        @Test
        @DisplayName("저장이 아니라 초안이라 200 이다 (201 아님, id 없음)")
        void isOkNotCreated() throws Exception {
            when(proposalAssemblyService.draftForTeam(USER_ID, TEAM_ID))
                    .thenReturn(draft("USER_TO_TEAM"));

            mockMvc.perform(post("/api/matching/proposals/user-to-team")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamId\":100}")
                            .principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").doesNotExist())
                    .andExpect(jsonPath("$.data.proposalId").doesNotExist());
        }

        @Test
        @DisplayName("응답 키는 direction/teamId/userId/synergyScore/summary/message 다")
        void responseShape() throws Exception {
            when(proposalAssemblyService.draftForTeam(anyLong(), anyLong()))
                    .thenReturn(draft("USER_TO_TEAM"));

            mockMvc.perform(post("/api/matching/proposals/user-to-team")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamId\":100}")
                            .principal(auth()))
                    .andExpect(jsonPath("$.data.direction").value("USER_TO_TEAM"))
                    .andExpect(jsonPath("$.data.teamId").value(100))
                    .andExpect(jsonPath("$.data.userId").value(2))
                    .andExpect(jsonPath("$.data.synergyScore").value(0.87))
                    .andExpect(jsonPath("$.data.summary").value("한 줄 요약"))
                    .andExpect(jsonPath("$.data.message").value("안녕하세요, 지원합니다."));
        }

        @Test
        @DisplayName("항상 null 인 예약 필드는 내보내지 않는다 (언젠가 채워지는 값으로 오해된다)")
        void reservedFieldIsNotExposed() throws Exception {
            when(proposalAssemblyService.draftForTeam(anyLong(), anyLong()))
                    .thenReturn(draft("USER_TO_TEAM"));

            mockMvc.perform(post("/api/matching/proposals/user-to-team")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamId\":100}")
                            .principal(auth()))
                    .andExpect(jsonPath("$.data.portfolioRoleFitScore").doesNotExist());
        }

        @Test
        @DisplayName("principal 이 userId 자리, 바디가 teamId 자리다")
        void argumentOrder() throws Exception {
            when(proposalAssemblyService.draftForTeam(anyLong(), anyLong()))
                    .thenReturn(draft("USER_TO_TEAM"));

            mockMvc.perform(post("/api/matching/proposals/user-to-team")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"teamId\":100}")
                    .principal(auth()));

            verify(proposalAssemblyService).draftForTeam(USER_ID, TEAM_ID);
        }

        @Test
        @DisplayName("teamId 가 없으면 서비스까지 가지 않고 400 이다 (LLM 호출 전에 막는다)")
        void teamIdIsRequired() throws Exception {
            mockMvc.perform(post("/api/matching/proposals/user-to-team")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("입력값 검증에 실패했습니다."))
                    .andExpect(jsonPath("$.data.teamId").value("teamId 는 필수입니다."));

            verifyNoInteractions(proposalAssemblyService);
        }

        @Test
        @DisplayName("추천에 뜬 적 없는 팀이면 404 다 — synergyScore 의 출처가 추천 이력뿐이다")
        void withoutRecommendationIs404() throws Exception {
            when(proposalAssemblyService.draftForTeam(anyLong(), anyLong()))
                    .thenThrow(new MateonException(ErrorCode.RECOMMENDATION_NOT_FOUND));

            mockMvc.perform(post("/api/matching/proposals/user-to-team")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamId\":100}")
                            .principal(auth()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(ErrorCode.RECOMMENDATION_NOT_FOUND.getMessage()));
        }
    }

    @Nested
    @DisplayName("POST /team-to-user (역제안 문구)")
    class DraftForUser {

        @Test
        @DisplayName("인자 순서는 (teamId, 대상 userId, 요청자 팀장) 다")
        void argumentOrder() throws Exception {
            when(proposalAssemblyService.draftForUser(TEAM_ID, TARGET_USER_ID, USER_ID))
                    .thenReturn(draft("TEAM_TO_USER"));

            mockMvc.perform(post("/api/matching/proposals/team-to-user")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamId\":100,\"userId\":2}")
                            .principal(auth()))
                    .andExpect(status().isOk());

            verify(proposalAssemblyService).draftForUser(TEAM_ID, TARGET_USER_ID, USER_ID);
        }

        @Test
        @DisplayName("응답 모양은 반대 방향과 같고 direction 만 다르다 (편집 화면을 하나로 쓴다)")
        void sameShapeDifferentDirection() throws Exception {
            when(proposalAssemblyService.draftForUser(anyLong(), anyLong(), anyLong()))
                    .thenReturn(draft("TEAM_TO_USER"));

            mockMvc.perform(post("/api/matching/proposals/team-to-user")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamId\":100,\"userId\":2}")
                            .principal(auth()))
                    .andExpect(jsonPath("$.data.direction").value("TEAM_TO_USER"))
                    .andExpect(jsonPath("$.data.teamId").value(100))
                    .andExpect(jsonPath("$.data.userId").value(2))
                    .andExpect(jsonPath("$.data.summary").value("한 줄 요약"))
                    .andExpect(jsonPath("$.data.message").value("안녕하세요, 지원합니다."));
        }

        @Test
        @DisplayName("teamId 와 userId 둘 다 필수다")
        void bothIdsRequired() throws Exception {
            mockMvc.perform(post("/api/matching/proposals/team-to-user")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":2}")
                            .principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.teamId").value("teamId 는 필수입니다."));

            verify(proposalAssemblyService, never()).draftForUser(anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("팀장이 아니면 403 이 아니라 400 이다")
        void forbiddenIs400() throws Exception {
            when(proposalAssemblyService.draftForUser(anyLong(), anyLong(), anyLong()))
                    .thenThrow(new MateonException(ErrorCode.FORBIDDEN_ACCESS));

            mockMvc.perform(post("/api/matching/proposals/team-to-user")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamId\":100,\"userId\":2}")
                            .principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ErrorCode.FORBIDDEN_ACCESS.getMessage()));
        }

        @Test
        @DisplayName("AI 서버가 죽어 있으면 503 이다 (초안이라 재시도가 안전하다)")
        void aiUnavailableIs503() throws Exception {
            when(proposalAssemblyService.draftForUser(anyLong(), anyLong(), anyLong()))
                    .thenThrow(new MateonException(ErrorCode.AI_SERVER_UNAVAILABLE));

            mockMvc.perform(post("/api/matching/proposals/team-to-user")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamId\":100,\"userId\":2}")
                            .principal(auth()))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private ProposalDraftResponseDTO draft(String direction) {
        return new ProposalDraftResponseDTO(direction, TEAM_ID, TARGET_USER_ID, 0.87,
                "한 줄 요약", "안녕하세요, 지원합니다.");
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of());
    }
}
