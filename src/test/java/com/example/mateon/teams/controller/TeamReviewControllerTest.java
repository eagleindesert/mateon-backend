package com.example.mateon.teams.controller;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.teams.dto.response.TeamReviewTargetsResponseDTO;
import com.example.mateon.teams.service.TeamCompletionService;
import com.example.mateon.teams.service.TeamReviewService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 활동 종료와 팀원 평가 API.
 *
 * <p>이 컨트롤러는 두 서비스를 물고 있다 — 종료는 {@code TeamCompletionService}, 평가는
 * {@code TeamReviewService}. 종료가 평가의 전제라 한 화면에서 이어지지만 계층이 다르므로,
 * 각 엔드포인트가 <b>어느 쪽으로 가는지</b>를 못박아 둔다.
 *
 * <p>평가 점수 범위(1~5)는 여기서 막는 게 중요하다. 협업 온도는 누적 평균이라 범위를 벗어난
 * 값이 한 번 들어가면 <b>되돌릴 수 없다</b> — 평가는 제출 후 수정·삭제가 없고, 온도는 집계 행에
 * 이미 반영된 뒤다. 서비스에 도달하기 전에 400 으로 끊어야 한다.
 *
 * <p>종료와 제출은 돌려줄 데이터가 없어 {@code ApiResponse.success(null)} 을 쓴다. 이건
 * {@code ApiResponse.success("문구")} 를 쓰는 {@code TeamController} 쪽과 달리 {@code message}
 * 가 정상적으로 {@code "성공"} 이고 {@code data} 가 null 인 <b>제대로 된</b> 모양이다.
 */
class TeamReviewControllerTest {

    private static final long USER_ID = 1L;
    private static final long TEAM_ID = 7L;

    private TeamCompletionService teamCompletionService;
    private TeamReviewService teamReviewService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        teamCompletionService = mock(TeamCompletionService.class);
        teamReviewService = mock(TeamReviewService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TeamReviewController(teamCompletionService, teamReviewService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("POST /api/teams/{teamId}/complete")
    class Complete {

        @Test
        @DisplayName("종료는 완료 서비스로 가고 data 는 null 이다")
        void delegatesToCompletionService() throws Exception {
            mockMvc.perform(post("/api/teams/7/complete").principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("성공"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            verify(teamCompletionService).completeByLeader(TEAM_ID, USER_ID);
            verifyNoInteractions(teamReviewService);
        }

        @Test
        @DisplayName("팀장이 아니면 400 이다 (403 아님)")
        void forbiddenIs400() throws Exception {
            doThrow(new MateonException(ErrorCode.FORBIDDEN_ACCESS))
                    .when(teamCompletionService).completeByLeader(anyLong(), anyLong());

            mockMvc.perform(post("/api/teams/7/complete").principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ErrorCode.FORBIDDEN_ACCESS.getMessage()));
        }

        @Test
        @DisplayName("이미 종료된 팀은 400 TEAM_ALREADY_ENDED 다 — 평가 기간이 연장되면 안 된다")
        void alreadyEnded() throws Exception {
            doThrow(new MateonException(ErrorCode.TEAM_ALREADY_ENDED))
                    .when(teamCompletionService).completeByLeader(anyLong(), anyLong());

            mockMvc.perform(post("/api/teams/7/complete").principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ErrorCode.TEAM_ALREADY_ENDED.getMessage()));
        }
    }

    @Nested
    @DisplayName("GET /api/teams/{teamId}/reviews/targets")
    class Targets {

        @Test
        @DisplayName("평가 마감 시각과 대상별 제출 여부가 함께 내려간다")
        void responseShape() throws Exception {
            when(teamReviewService.getTargets(TEAM_ID, USER_ID)).thenReturn(targets());

            mockMvc.perform(get("/api/teams/7/reviews/targets").principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.teamId").value(7))
                    .andExpect(jsonPath("$.data.teamTitle").value("백엔드 구합니다"))
                    .andExpect(jsonPath("$.data.endedAt").exists())
                    .andExpect(jsonPath("$.data.reviewDeadline").exists())
                    .andExpect(jsonPath("$.data.targets[0].userId").value(2))
                    .andExpect(jsonPath("$.data.targets[0].name").value("팀원A"))
                    .andExpect(jsonPath("$.data.targets[0].alreadyReviewed").value(false))
                    .andExpect(jsonPath("$.data.targets[1].alreadyReviewed").value(true));
        }

        @Test
        @DisplayName("종료되지 않은 팀은 400 TEAM_NOT_ENDED 다")
        void notEnded() throws Exception {
            when(teamReviewService.getTargets(anyLong(), anyLong()))
                    .thenThrow(new MateonException(ErrorCode.TEAM_NOT_ENDED));

            mockMvc.perform(get("/api/teams/7/reviews/targets").principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ErrorCode.TEAM_NOT_ENDED.getMessage()));
        }

        @Test
        @DisplayName("평가 기간이 지났으면 400 REVIEW_PERIOD_EXPIRED 다")
        void expired() throws Exception {
            when(teamReviewService.getTargets(anyLong(), anyLong()))
                    .thenThrow(new MateonException(ErrorCode.REVIEW_PERIOD_EXPIRED));

            mockMvc.perform(get("/api/teams/7/reviews/targets").principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ErrorCode.REVIEW_PERIOD_EXPIRED.getMessage()));
        }

        @Test
        @DisplayName("팀원이 아니면 400 NOT_TEAM_MEMBER 다")
        void notMember() throws Exception {
            when(teamReviewService.getTargets(anyLong(), anyLong()))
                    .thenThrow(new MateonException(ErrorCode.NOT_TEAM_MEMBER));

            mockMvc.perform(get("/api/teams/7/reviews/targets").principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ErrorCode.NOT_TEAM_MEMBER.getMessage()));
        }
    }

    @Nested
    @DisplayName("POST /api/teams/{teamId}/reviews — 점수 범위는 서비스 전에 막는다")
    class Submit {

        @Test
        @DisplayName("정상 제출은 200 이고 data 가 null 이다")
        void submits() throws Exception {
            mockMvc.perform(post("/api/teams/7/reviews")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reviews\":[{\"revieweeId\":2,\"rating\":5}]}")
                            .principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").doesNotExist());

            verify(teamReviewService).submit(eq(TEAM_ID), eq(USER_ID), any());
        }

        @Test
        @DisplayName("빈 배열은 400 이다 (아무것도 안 낸 것과 낸 것을 구분한다)")
        void emptyReviews() throws Exception {
            mockMvc.perform(post("/api/teams/7/reviews")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reviews\":[]}")
                            .principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.reviews").value("평가 내용이 비어 있습니다."));

            verify(teamReviewService, never()).submit(anyLong(), anyLong(), any());
        }

        /**
         * 범위를 벗어난 점수가 한 번 반영되면 되돌릴 수 없다 — 평가는 수정·삭제가 없고 협업
         * 온도는 이미 누적 평균에 들어간 뒤다. 그래서 {@code @Valid} 가 마지막 방어선이다.
         */
        @Test
        @DisplayName("6점은 서비스까지 가지 않고 400 이다 (온도는 되돌릴 수 없다)")
        void ratingAboveRange() throws Exception {
            mockMvc.perform(post("/api/teams/7/reviews")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reviews\":[{\"revieweeId\":2,\"rating\":6}]}")
                            .principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("입력값 검증에 실패했습니다."));

            verifyNoInteractions(teamReviewService);
        }

        @Test
        @DisplayName("0점도 막힌다 (1~5 의 아래쪽 경계)")
        void ratingBelowRange() throws Exception {
            mockMvc.perform(post("/api/teams/7/reviews")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reviews\":[{\"revieweeId\":2,\"rating\":0}]}")
                            .principal(auth()))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(teamReviewService);
        }

        @Test
        @DisplayName("1점과 5점은 통과한다 (경계값)")
        void boundaryRatingsPass() throws Exception {
            mockMvc.perform(post("/api/teams/7/reviews")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reviews\":[{\"revieweeId\":2,\"rating\":1},"
                                    + "{\"revieweeId\":3,\"rating\":5}]}")
                            .principal(auth()))
                    .andExpect(status().isOk());

            verify(teamReviewService).submit(eq(TEAM_ID), eq(USER_ID), any());
        }

        @Test
        @DisplayName("여러 건 중 하나만 범위를 벗어나도 전부 막힌다 (부분 반영이 없다)")
        void oneBadItemBlocksAll() throws Exception {
            mockMvc.perform(post("/api/teams/7/reviews")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reviews\":[{\"revieweeId\":2,\"rating\":5},"
                                    + "{\"revieweeId\":3,\"rating\":99}]}")
                            .principal(auth()))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(teamReviewService);
        }

        @Test
        @DisplayName("이미 제출했으면 400 ALREADY_REVIEWED 다")
        void alreadyReviewed() throws Exception {
            doThrow(new MateonException(ErrorCode.ALREADY_REVIEWED))
                    .when(teamReviewService).submit(anyLong(), anyLong(), any());

            mockMvc.perform(post("/api/teams/7/reviews")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reviews\":[{\"revieweeId\":2,\"rating\":5}]}")
                            .principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ErrorCode.ALREADY_REVIEWED.getMessage()));
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private TeamReviewTargetsResponseDTO targets() {
        LocalDateTime endedAt = LocalDateTime.of(2026, 8, 1, 12, 0);
        return new TeamReviewTargetsResponseDTO(TEAM_ID, "백엔드 구합니다", endedAt,
                endedAt.plusDays(14),
                List.of(new TeamReviewTargetsResponseDTO.Target(2L, "팀원A", "컴퓨터공학", false),
                        new TeamReviewTargetsResponseDTO.Target(3L, "팀원B", "디자인", true)));
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of());
    }
}
