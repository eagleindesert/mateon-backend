package com.example.mateon.matching.controller;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.GlobalExceptionHandler;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.models.Event;
import com.example.mateon.matching.domain.MatchingIntentSlot;
import com.example.mateon.matching.dto.response.TeamRecommendationResponseDTO;
import com.example.mateon.matching.dto.response.UserRecommendationResponseDTO;
import com.example.mateon.matching.service.RecommendationReasonService;
import com.example.mateon.matching.service.RecommendationService;
import com.example.mateon.matching.service.TeamToUserRecommendationService;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.service.CollaborationTemperatureCalculator;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
 * 추천 API 의 전선 계약 — 파라미터 기본값, 빈 결과, 그리고 <b>상태코드 매핑</b>.
 *
 * <p>이 컨트롤러의 상태코드는 직관에 반한다. Javadoc 은 "팀장이 아니면 403" 이라고 적혀 있지만
 * {@code FORBIDDEN_ACCESS} 의 실제 상태는 <b>400</b> 이고, "추천에 없으면 404" 는 실제로 404 다
 * ({@code RECOMMENDATION_NOT_FOUND} 는 404 를 명시한 세 개 중 하나). 즉 같은 컨트롤러 안에서
 * 문서와 코드가 한쪽만 맞다. 프론트는 코드 쪽에 맞춰 이미 붙어 있으므로 <b>실제 동작</b>을
 * 고정한다 — 나중에 봉투를 정리한다면 이 테스트들이 함께 빨개져서 프론트도 같이 고쳐야 함을 알린다.
 *
 * <p>두 번째는 <b>빈 결과가 404 가 아니라 빈 배열</b>이라는 것. "아직 후보가 없음"은 신규 사용자의
 * 정상 상태다 (의도 추출 직후엔 흔하다).
 *
 * <p>세 번째는 {@code limit} 기본값 10 이다. 이 값은 AI 서버가 점수를 매겨 주는 상한과 맞물려
 * 있어서 서버에서 조용히 바꾸면 프론트 카드 목록의 길이가 어긋난다.
 */
class RecommendationControllerTest {

    private static final long USER_ID = 1L;
    private static final long TEAM_ID = 100L;
    private static final long TARGET_USER_ID = 2L;

    private RecommendationService recommendationService;
    private TeamToUserRecommendationService teamToUserRecommendationService;
    private RecommendationReasonService recommendationReasonService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        recommendationService = mock(RecommendationService.class);
        teamToUserRecommendationService = mock(TeamToUserRecommendationService.class);
        recommendationReasonService = mock(RecommendationReasonService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RecommendationController(recommendationService,
                        teamToUserRecommendationService, recommendationReasonService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("GET /user-to-team")
    class RecommendTeams {

        @Test
        @DisplayName("eventId 를 생략하면 null 이 넘어가고 limit 은 10 이다")
        void defaults() throws Exception {
            when(recommendationService.recommendTeams(eq(USER_ID), isNull(), eq(10)))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/matching/recommendations/user-to-team").principal(auth()))
                    .andExpect(status().isOk());

            verify(recommendationService).recommendTeams(USER_ID, null, 10);
        }

        @Test
        @DisplayName("eventId/limit 은 그대로 서비스에 넘어간다")
        void passesParams() throws Exception {
            when(recommendationService.recommendTeams(eq(USER_ID), eq(7L), eq(3)))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/matching/recommendations/user-to-team")
                            .param("eventId", "7").param("limit", "3")
                            .principal(auth()))
                    .andExpect(status().isOk());

            verify(recommendationService).recommendTeams(USER_ID, 7L, 3);
        }

        @Test
        @DisplayName("추천할 팀이 없으면 빈 배열이다 (404 가 아니다)")
        void emptyIsNot404() throws Exception {
            when(recommendationService.recommendTeams(anyLong(), any(), anyInt())).thenReturn(List.of());

            mockMvc.perform(get("/api/matching/recommendations/user-to-team").principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("카드에 필요한 키가 모두 있다 — score/label 과 연결 활동 정보")
        void cardShape() throws Exception {
            when(recommendationService.recommendTeams(anyLong(), any(), anyInt()))
                    .thenReturn(List.of(teamCard()));

            mockMvc.perform(get("/api/matching/recommendations/user-to-team").principal(auth()))
                    .andExpect(jsonPath("$.data[0].teamId").value(100))
                    .andExpect(jsonPath("$.data[0].title").value("백엔드 구합니다"))
                    .andExpect(jsonPath("$.data[0].role[0]").value("백엔드"))
                    .andExpect(jsonPath("$.data[0].requiredSkills[0]").value("Spring"))
                    .andExpect(jsonPath("$.data[0].capacity").value(4))
                    .andExpect(jsonPath("$.data[0].currentMemberCount").value(2))
                    .andExpect(jsonPath("$.data[0].eventId").value(7))
                    .andExpect(jsonPath("$.data[0].connectedActivityTitle").value("교내 해커톤"))
                    .andExpect(jsonPath("$.data[0].connectedActivitySummary").value("이틀간 진행"))
                    .andExpect(jsonPath("$.data[0].leaderId").value(9))
                    .andExpect(jsonPath("$.data[0].recruitmentEndDate").exists())
                    .andExpect(jsonPath("$.data[0].score").value(0.87))
                    .andExpect(jsonPath("$.data[0].label").value("역할이 맞습니다"));
        }

        @Test
        @DisplayName("자율 팀(활동 미연결)은 활동 관련 키가 null 이다 — 키 자체가 사라지지 않는다")
        void standaloneTeamHasNullActivity() throws Exception {
            Team team = team();
            team.setEventId(null);
            when(recommendationService.recommendTeams(anyLong(), any(), anyInt()))
                    .thenReturn(List.of(new TeamRecommendationResponseDTO(team, null, 2, 0.5, "라벨")));

            mockMvc.perform(get("/api/matching/recommendations/user-to-team").principal(auth()))
                    .andExpect(jsonPath("$.data[0].eventId").doesNotExist())
                    .andExpect(jsonPath("$.data[0].connectedActivityTitle").doesNotExist())
                    .andExpect(jsonPath("$.data[0].teamId").value(100));
        }

        @Test
        @DisplayName("의도 추출 전이면 400 이다 (추천의 전제 조건)")
        void intentRequiredIs400() throws Exception {
            when(recommendationService.recommendTeams(anyLong(), any(), anyInt()))
                    .thenThrow(new MateonException(ErrorCode.MATCHING_INTENT_REQUIRED));

            mockMvc.perform(get("/api/matching/recommendations/user-to-team").principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(ErrorCode.MATCHING_INTENT_REQUIRED.getMessage()));
        }

        @Test
        @DisplayName("limit 이 숫자가 아니면 서비스까지 가지 않고 400 이다")
        void nonNumericLimitIs400() throws Exception {
            mockMvc.perform(get("/api/matching/recommendations/user-to-team")
                            .param("limit", "abc")
                            .principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("입력값 검증에 실패했습니다."));

            verifyNoInteractions(recommendationService);
        }
    }

    @Nested
    @DisplayName("GET /team-to-user (역제안)")
    class RecommendUsers {

        /**
         * 현재 동작을 그대로 고정한다 — <b>이건 흠결이다.</b>
         *
         * <p>{@code GlobalExceptionHandler} 에는 {@code MissingServletRequestParameterException}
         * 핸들러가 없어서 catch-all {@code Exception} 로 떨어진다. 그래서 "파라미터를 빠뜨렸다"
         * 는 클라이언트 실수가 <b>500</b> 으로 나간다. 같은 성격의 실수인 타입 불일치
         * ({@code ?teamId=abc})는 전용 핸들러가 있어서 400 인데 말이다.
         *
         * <p>서버 입장에서는 5xx 알람이 클라이언트 실수로 울리고, 프론트 입장에서는 "잠시 후
         * 다시 시도" 안내가 뜬다 (고쳐도 영원히 안 된다). 고칠 값어치가 있지만 그건 별개 변경이라
         * 여기서는 지금 나가는 응답을 못박아 둔다 — 핸들러가 추가되면 이 테스트가 빨개져서
         * 의도한 변경임을 확인하게 된다.
         */
        @Test
        @DisplayName("teamId 를 빠뜨리면 500 이다 (400 이어야 마땅하지만 전용 핸들러가 없다)")
        void missingTeamIdFallsToCatchAll() throws Exception {
            mockMvc.perform(get("/api/matching/recommendations/team-to-user").principal(auth()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false));

            verifyNoInteractions(teamToUserRecommendationService);
        }

        @Test
        @DisplayName("teamId 가 숫자가 아니면 400 이다 (이쪽은 전용 핸들러가 있다)")
        void nonNumericTeamIdIs400() throws Exception {
            mockMvc.perform(get("/api/matching/recommendations/team-to-user")
                            .param("teamId", "abc")
                            .principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("입력값 검증에 실패했습니다."));

            verifyNoInteractions(teamToUserRecommendationService);
        }

        @Test
        @DisplayName("limit 기본값은 여기서도 10 이고, principal 이 leaderUserId 자리로 간다")
        void defaultsAndLeaderPosition() throws Exception {
            when(teamToUserRecommendationService.recommendUsers(TEAM_ID, USER_ID, 10))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/matching/recommendations/team-to-user")
                            .param("teamId", "100")
                            .principal(auth()))
                    .andExpect(status().isOk());

            // 두 번째 인자가 요청자다. teamId 와 자리가 바뀌면 "팀 100 의 팀장인가"가
            // "팀 1 의 팀장인가"로 바뀌어 권한 검사가 통째로 엉뚱해진다.
            verify(teamToUserRecommendationService).recommendUsers(TEAM_ID, USER_ID, 10);
        }

        @Test
        @DisplayName("후보 카드에 협업 온도가 함께 실린다 (프론트 배지)")
        void userCardShape() throws Exception {
            when(teamToUserRecommendationService.recommendUsers(anyLong(), anyLong(), anyInt()))
                    .thenReturn(List.of(userCard()));

            mockMvc.perform(get("/api/matching/recommendations/team-to-user")
                            .param("teamId", "100")
                            .principal(auth()))
                    .andExpect(jsonPath("$.data[0].userId").value(2))
                    .andExpect(jsonPath("$.data[0].name").value("김후보"))
                    .andExpect(jsonPath("$.data[0].school").value("메이트대"))
                    .andExpect(jsonPath("$.data[0].major").value("컴퓨터공학"))
                    .andExpect(jsonPath("$.data[0].desiredRoles[0]").value("디자이너"))
                    .andExpect(jsonPath("$.data[0].skills[0]").value("Figma"))
                    .andExpect(jsonPath("$.data[0].experienceLevel").value("입문"))
                    .andExpect(jsonPath("$.data[0].activityStyle").value("온라인"))
                    .andExpect(jsonPath("$.data[0].collaborationTemperature").value(36.5))
                    .andExpect(jsonPath("$.data[0].score").value(0.72))
                    .andExpect(jsonPath("$.data[0].label").value("성향이 맞습니다"));
        }

        @Test
        @DisplayName("팀장이 아니면 403 이 아니라 400 이다 (Javadoc 의 403 은 실제와 다르다)")
        void forbiddenIs400NotForbidden() throws Exception {
            when(teamToUserRecommendationService.recommendUsers(anyLong(), anyLong(), anyInt()))
                    .thenThrow(new MateonException(ErrorCode.FORBIDDEN_ACCESS));

            mockMvc.perform(get("/api/matching/recommendations/team-to-user")
                            .param("teamId", "100")
                            .principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ErrorCode.FORBIDDEN_ACCESS.getMessage()));
        }

        @Test
        @DisplayName("팀 임베딩이 아직 안 만들어졌으면 400 이다 (곧 재시도하면 되는 상태)")
        void embeddingNotReadyIs400() throws Exception {
            when(teamToUserRecommendationService.recommendUsers(anyLong(), anyLong(), anyInt()))
                    .thenThrow(new MateonException(ErrorCode.TEAM_EMBEDDING_NOT_READY));

            mockMvc.perform(get("/api/matching/recommendations/team-to-user")
                            .param("teamId", "100")
                            .principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ErrorCode.TEAM_EMBEDDING_NOT_READY.getMessage()));
        }
    }

    @Nested
    @DisplayName("POST /reason/user-to-team")
    class ExplainTeam {

        @Test
        @DisplayName("응답은 data.reason 하나다")
        void returnsReason() throws Exception {
            when(recommendationReasonService.explainTeam(USER_ID, TEAM_ID))
                    .thenReturn("이 팀은 백엔드를 찾고 있고 당신의 기술과 겹칩니다.");

            mockMvc.perform(post("/api/matching/recommendations/reason/user-to-team")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamId\":100}")
                            .principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reason")
                            .value("이 팀은 백엔드를 찾고 있고 당신의 기술과 겹칩니다."));
        }

        @Test
        @DisplayName("teamId 가 없으면 서비스까지 가지 않고 400 이다")
        void teamIdIsRequired() throws Exception {
            mockMvc.perform(post("/api/matching/recommendations/reason/user-to-team")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.teamId").value("teamId 는 필수입니다."));

            verifyNoInteractions(recommendationReasonService);
        }

        @Test
        @DisplayName("추천에 뜬 적 없는 팀이면 404 다 (선행 호출이 필요하다는 신호)")
        void unknownTeamIs404() throws Exception {
            when(recommendationReasonService.explainTeam(anyLong(), anyLong()))
                    .thenThrow(new MateonException(ErrorCode.RECOMMENDATION_NOT_FOUND));

            mockMvc.perform(post("/api/matching/recommendations/reason/user-to-team")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamId\":100}")
                            .principal(auth()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(ErrorCode.RECOMMENDATION_NOT_FOUND.getMessage()));
        }
    }

    @Nested
    @DisplayName("POST /reason/team-to-user")
    class ExplainUser {

        @Test
        @DisplayName("인자 순서는 (teamId, 대상 userId, 요청자) 다 — 뒤바뀌면 남의 이유가 나간다")
        void argumentOrder() throws Exception {
            when(recommendationReasonService.explainUser(TEAM_ID, TARGET_USER_ID, USER_ID))
                    .thenReturn("이 사람은 디자이너입니다.");

            mockMvc.perform(post("/api/matching/recommendations/reason/team-to-user")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamId\":100,\"userId\":2}")
                            .principal(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reason").value("이 사람은 디자이너입니다."));

            verify(recommendationReasonService).explainUser(TEAM_ID, TARGET_USER_ID, USER_ID);
        }

        @Test
        @DisplayName("teamId 와 userId 둘 다 필수다")
        void bothIdsRequired() throws Exception {
            mockMvc.perform(post("/api/matching/recommendations/reason/team-to-user")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamId\":100}")
                            .principal(auth()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.userId").value("userId 는 필수입니다."));

            verify(recommendationReasonService, never()).explainUser(anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("팀장이 아니면 여기서도 400 이다")
        void forbiddenIs400() throws Exception {
            when(recommendationReasonService.explainUser(anyLong(), anyLong(), anyLong()))
                    .thenThrow(new MateonException(ErrorCode.FORBIDDEN_ACCESS));

            mockMvc.perform(post("/api/matching/recommendations/reason/team-to-user")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"teamId\":100,\"userId\":2}")
                            .principal(auth()))
                    .andExpect(status().isBadRequest());
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private TeamRecommendationResponseDTO teamCard() {
        Event event = new Event();
        event.setId(7L);
        event.setTitle("교내 해커톤");
        event.setSummarizedDescription("이틀간 진행");
        return new TeamRecommendationResponseDTO(team(), event, 2, 0.87, "역할이 맞습니다");
    }

    private Team team() {
        Team team = new Team();
        team.setId(TEAM_ID);
        team.setTitle("백엔드 구합니다");
        team.setRole(List.of("백엔드"));
        team.setRequiredSkills(List.of("Spring"));
        team.setCapacity(4);
        team.setEventId(7L);
        team.setLeaderUserId(9L);
        team.setRecruitmentEndDate(LocalDate.of(2026, 12, 31));
        return team;
    }

    private UserRecommendationResponseDTO userCard() {
        User user = User.builder()
                .id(TARGET_USER_ID)
                .name("김후보")
                .school("메이트대")
                .major("컴퓨터공학")
                .grade("3")
                .tagline("협업 좋아합니다")
                .build();

        MatchingIntentSlot slot = new MatchingIntentSlot(user);
        slot.update(null, List.of("디자이너"), List.of("Figma"), List.of("UX"),
                "포트폴리오", "온라인", "입문", "임베딩 원문");

        return new UserRecommendationResponseDTO(user, slot,
                CollaborationTemperatureCalculator.INITIAL, 0.72, "성향이 맞습니다");
    }

    private Authentication auth() {
        return new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of());
    }
}
