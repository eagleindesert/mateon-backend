package com.example.mateon.teams.controller;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.teams.domain.OfferStatus;
import com.example.mateon.teams.dto.response.TeamOfferResponseDTO;
import com.example.mateon.teams.service.TeamOfferService;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 역제안 API 의 전선 계약.
 *
 * <p>
 * 이 컨트롤러의 경로들은 <b>같은 {@code /api/teams} 아래에서 이름만으로 갈린다</b>:
 * {@code /{teamId}/offers}(팀장이 보낸 목록)와 {@code /offers/me}(내가 받은 목록)가 그렇고,
 * {@code /offers/{offerId}} 는 PATCH 면 응답, DELETE 면 회수다. 매핑을 잘못 건드리면
 * "팀 5번의 제안 목록"이 "내가 받은 목록"으로 조용히 바뀐다 — 둘 다 같은 DTO 배열이라 응답
 * 모양으로는 구분되지 않는다.
 *
 * <p>
 * 또 하나는 <b>{@code accepted} 가 {@code Boolean} 이라는 것</b>이다. {@code boolean} 이었다면
 * 누락 시 기본값 {@code false}(=거절)로 조용히 처리됐을 텐데, 래퍼 타입 + {@code @NotNull} 이라
 * 400 으로 막힌다. 사용자의 수락 의사를 서버가 임의로 거절로 해석하는 사고를 막는 유일한 장치다.
 *
 * <p>
 * {@code aiScore}/{@code aiLabel} 이 응답에 실려 나가는 것도 확인한다 — 서버가 추천 이력에서
 * 찾아 넣은 값이고, 팀장 화면이 "왜 이 사람을 추천했는지" 를 여기서 읽는다.
 */
class TeamOfferControllerTest {

    private static final long USER_ID = 1L;
    private static final long TEAM_ID = 7L;
    private static final long OFFER_ID = 50L;
    private static final long TARGET_ID = 2L;

    private TeamOfferService teamOfferService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        teamOfferService = mock(TeamOfferService.class);
        mockMvc = MockMvcBuilders
          .standaloneSetup(new TeamOfferController(teamOfferService))
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();
    }

    @Nested
    @DisplayName("POST /api/teams/{teamId}/offers")
    class CreateOffer {

        @Test
        @DisplayName("teamId 는 경로에서, 대상 유저와 문구는 본문에서, 팀장은 principal 에서 온다")
        void argumentSources() throws Exception {
            when(teamOfferService.createOffer(TEAM_ID, TARGET_ID, "함께해요", USER_ID))
              .thenReturn(offer(OfferStatus.PENDING));

            mockMvc.perform(post("/api/teams/7/offers")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"userId\":2,\"message\":\"함께해요\"}")
              .principal(auth()))
              .andExpect(status().isOk());

            verify(teamOfferService).createOffer(TEAM_ID, TARGET_ID, "함께해요", USER_ID);
        }

        @Test
        @DisplayName("생성인데 201 이 아니라 200 이다 (201 은 팀 모집글 작성뿐이다)")
        void isOkNotCreated() throws Exception {
            when(teamOfferService.createOffer(anyLong(), anyLong(), any(), anyLong()))
              .thenReturn(offer(OfferStatus.PENDING));

            mockMvc.perform(post("/api/teams/7/offers")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"userId\":2}")
              .principal(auth()))
              .andExpect(status().isOk());
        }

        @Test
        @DisplayName("응답에 AI 점수와 근거가 실린다 (팀장 화면이 추천 근거를 여기서 읽는다)")
        void carriesAiScore() throws Exception {
            when(teamOfferService.createOffer(anyLong(), anyLong(), any(), anyLong()))
              .thenReturn(offer(OfferStatus.PENDING));

            mockMvc.perform(post("/api/teams/7/offers")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"userId\":2}")
              .principal(auth()))
              .andExpect(jsonPath("$.data.offerId").value(50))
              .andExpect(jsonPath("$.data.teamId").value(7))
              .andExpect(jsonPath("$.data.targetUserId").value(2))
              .andExpect(jsonPath("$.data.targetUserName").value("대상유저"))
              .andExpect(jsonPath("$.data.status").value("PENDING"))
              .andExpect(jsonPath("$.data.aiScore").value(0.91))
              .andExpect(jsonPath("$.data.aiLabel").value("역할이 맞습니다"));
        }

        @Test
        @DisplayName("message 는 선택이다 (userId 만으로도 보낼 수 있다)")
        void messageIsOptional() throws Exception {
            when(teamOfferService.createOffer(anyLong(), anyLong(), isNull(), anyLong()))
              .thenReturn(offer(OfferStatus.PENDING));

            mockMvc.perform(post("/api/teams/7/offers")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"userId\":2}")
              .principal(auth()))
              .andExpect(status().isOk());

            verify(teamOfferService).createOffer(TEAM_ID, TARGET_ID, null, USER_ID);
        }

        @Test
        @DisplayName("userId 가 없으면 서비스까지 가지 않고 400 이다")
        void userIdIsRequired() throws Exception {
            mockMvc.perform(post("/api/teams/7/offers")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"message\":\"함께해요\"}")
              .principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.data.userId").value("제안할 유저를 지정해주세요."));

            verifyNoInteractions(teamOfferService);
        }

        @Test
        @DisplayName("중복 제안은 400 DUPLICATE_RESOURCE 다")
        void duplicateIs400() throws Exception {
            when(teamOfferService.createOffer(anyLong(), anyLong(), any(), anyLong()))
              .thenThrow(new MateonException(ErrorCode.DUPLICATE_RESOURCE));

            mockMvc.perform(post("/api/teams/7/offers")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"userId\":2}")
              .principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value(ErrorCode.DUPLICATE_RESOURCE.getMessage()));
        }

        @Test
        @DisplayName("팀장이 아니면 400 이다 (403 아님)")
        void forbiddenIs400() throws Exception {
            when(teamOfferService.createOffer(anyLong(), anyLong(), any(), anyLong()))
              .thenThrow(new MateonException(ErrorCode.FORBIDDEN_ACCESS));

            mockMvc.perform(post("/api/teams/7/offers")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"userId\":2}")
              .principal(auth()))
              .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("목록 — 경로 이름 하나로 방향이 갈린다")
    class Lists {

        @Test
        @DisplayName("/{teamId}/offers 는 팀이 보낸 목록이다")
        void teamOffers() throws Exception {
            when(teamOfferService.getTeamOffers(TEAM_ID, USER_ID)).thenReturn(List.of());

            mockMvc.perform(get("/api/teams/7/offers").principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data").isArray());

            verify(teamOfferService).getTeamOffers(TEAM_ID, USER_ID);
            verify(teamOfferService, never()).getMyOffers(anyLong());
        }

        @Test
        @DisplayName("/offers/me 는 내가 받은 목록이다 (teamId 를 받지 않는다)")
        void myOffers() throws Exception {
            when(teamOfferService.getMyOffers(USER_ID)).thenReturn(List.of(offer(OfferStatus.PENDING)));

            mockMvc.perform(get("/api/teams/offers/me").principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data[0].offerId").value(50));

            verify(teamOfferService).getMyOffers(USER_ID);
            verify(teamOfferService, never()).getTeamOffers(anyLong(), anyLong());
        }

        @Test
        @DisplayName("팀 제안 목록을 남이 보려 하면 400 이다")
        void teamOffersForbidden() throws Exception {
            when(teamOfferService.getTeamOffers(anyLong(), anyLong()))
              .thenThrow(new MateonException(ErrorCode.FORBIDDEN_ACCESS));

            mockMvc.perform(get("/api/teams/7/offers").principal(auth()))
              .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("PATCH /api/teams/offers/{offerId} — 수락/거절")
    class Respond {

        @Test
        @DisplayName("accepted 가 그대로 서비스에 넘어간다")
        void passesAccepted() throws Exception {
            when(teamOfferService.respond(eq(OFFER_ID), anyBoolean(), eq(USER_ID)))
              .thenReturn(offer(OfferStatus.ACCEPTED));

            mockMvc.perform(patch("/api/teams/offers/50")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"accepted\":true}")
              .principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.status").value("ACCEPTED"));

            verify(teamOfferService).respond(OFFER_ID, true, USER_ID);
        }

        @Test
        @DisplayName("거절도 200 이고 상태가 REJECTED 로 내려간다")
        void rejectIsOk() throws Exception {
            when(teamOfferService.respond(anyLong(), anyBoolean(), anyLong()))
              .thenReturn(offer(OfferStatus.REJECTED));

            mockMvc.perform(patch("/api/teams/offers/50")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"accepted\":false}")
              .principal(auth()))
              .andExpect(jsonPath("$.data.status").value("REJECTED"));

            verify(teamOfferService).respond(OFFER_ID, false, USER_ID);
        }

        /**
         * {@code accepted} 가 원시 {@code boolean} 이었다면 누락 시 {@code false} 로 바인딩돼
         * <b>수락이 거절로 처리</b>됐을 것이다. 되돌릴 수 없는 상태 전이라 400 으로 막는 게 맞다.
         */
        @Test
        @DisplayName("accepted 를 빠뜨리면 거절로 처리되지 않고 400 이다")
        void missingAcceptedIsNotSilentReject() throws Exception {
            mockMvc.perform(patch("/api/teams/offers/50")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}")
              .principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.data.accepted").value("수락 여부를 지정해주세요."));

            verify(teamOfferService, never()).respond(anyLong(), anyBoolean(), anyLong());
        }

        @Test
        @DisplayName("그 사이 정원이 찼으면 400 TEAM_RECRUITMENT_CLOSED 다")
        void closedSinceOffer() throws Exception {
            when(teamOfferService.respond(anyLong(), anyBoolean(), anyLong()))
              .thenThrow(new MateonException(ErrorCode.TEAM_RECRUITMENT_CLOSED));

            mockMvc.perform(patch("/api/teams/offers/50")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"accepted\":true}")
              .principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value(ErrorCode.TEAM_RECRUITMENT_CLOSED.getMessage()));
        }

        @Test
        @DisplayName("남에게 온 제안에 응답하면 400 FORBIDDEN_ACCESS 다")
        void notMyOffer() throws Exception {
            when(teamOfferService.respond(anyLong(), anyBoolean(), anyLong()))
              .thenThrow(new MateonException(ErrorCode.FORBIDDEN_ACCESS));

            mockMvc.perform(patch("/api/teams/offers/50")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"accepted\":true}")
              .principal(auth()))
              .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("DELETE /api/teams/offers/{offerId} — 회수")
    class Cancel {

        @Test
        @DisplayName("안내 문구가 message 가 아니라 data 에 담긴다 (봉투 오버로드 함정)")
        void messageLandsInData() throws Exception {
            mockMvc.perform(delete("/api/teams/offers/50").principal(auth()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.message").value("성공"))
              .andExpect(jsonPath("$.data").value("제안이 취소되었습니다."));

            verify(teamOfferService).cancelOffer(OFFER_ID, USER_ID);
        }

        @Test
        @DisplayName("이미 응답한 제안은 회수할 수 없다 (400)")
        void alreadyResponded() throws Exception {
            org.mockito.Mockito.doThrow(new MateonException(ErrorCode.OFFER_ALREADY_RESPONDED))
              .when(teamOfferService).cancelOffer(anyLong(), anyLong());

            mockMvc.perform(delete("/api/teams/offers/50").principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value(ErrorCode.OFFER_ALREADY_RESPONDED.getMessage()));
        }

        @Test
        @DisplayName("offerId 가 숫자가 아니면 400 이고 서비스까지 가지 않는다")
        void nonNumericIdIs400() throws Exception {
            mockMvc.perform(delete("/api/teams/offers/abc").principal(auth()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.message").value("입력값 검증에 실패했습니다."));

            verify(teamOfferService, never()).cancelOffer(anyLong(), anyLong());
        }
    }

    // --- 픽스처 -------------------------------------------------------------
    private TeamOfferResponseDTO offer(OfferStatus status) {
        return TeamOfferResponseDTO.builder()
          .offerId(OFFER_ID)
          .teamId(TEAM_ID)
          .teamTitle("백엔드 구합니다")
          .role(List.of("백엔드"))
          .capacity(4)
          .leaderId(USER_ID)
          .leaderName("팀장")
          .targetUserId(TARGET_ID)
          .targetUserName("대상유저")
          .targetUserSchool("메이트대")
          .targetUserMajor("컴퓨터공학")
          .message("함께해요")
          .aiScore(0.91)
          .aiLabel("역할이 맞습니다")
          .status(status)
          .build();
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of());
    }
}
