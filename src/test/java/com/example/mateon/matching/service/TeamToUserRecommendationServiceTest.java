package com.example.mateon.matching.service;

import com.example.mateon.matching.client.recommendation.RecommendationClient;
import com.example.mateon.matching.client.recommendation.RecommendationResponse;
import com.example.mateon.matching.client.recommendation.TeamToUserRecommendationRequest;
import com.example.mateon.matching.domain.MatchingIntentSlot;
import com.example.mateon.matching.dto.response.UserRecommendationResponseDTO;
import com.example.mateon.matching.dto.snapshot.UserDisplayInfo;
import com.example.mateon.matching.dto.snapshot.UserRecommendationSnapshot;
import com.example.mateon.support.TestEntities;
import com.example.mateon.teams.service.CollaborationTemperatureCalculator;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 역제안(팀→유저) 추천 오케스트레이션 — {@link RecommendationServiceTest} 의 거울상이다.
 *
 * <p>
 * 거울상이라는 게 정확히 위험 지점이다. 두 클래스는 구조가 같아서 한쪽을 고칠 때 다른 쪽을
 * 빠뜨리기 쉽고, 빠뜨려도 컴파일은 통과한다. 그래서 같은 규칙(정렬·필터·자르기 순서·기록 실패
 * 무시)을 양쪽에 각각 적어 둔다.
 *
 * <p>
 * 이쪽만의 것도 있다: 응답에 <b>협업 온도</b>가 붙는다. 평가를 한 번도 안 받은 유저는 집계
 * 행이 없는데, 그때 null 이 아니라 기준점 36.5 가 나가야 프론트가 온도 배지를 항상 그릴 수 있다.
 */
class TeamToUserRecommendationServiceTest {

    private static final long TEAM_ID = 100L;
    private static final long LEADER_ID = 1L;

    private RecommendationQueryService queryService;
    private RecommendationLogService logService;
    private RecommendationClient client;
    private TeamToUserRecommendationService service;

    @BeforeEach
    void setUp() {
        queryService = mock(RecommendationQueryService.class);
        logService = mock(RecommendationLogService.class);
        client = mock(RecommendationClient.class);
        service = new TeamToUserRecommendationService(queryService, logService, client);
    }

    @Test
    @DisplayName("후보가 없으면 AI 를 부르지 않는다")
    void noCandidatesSkipsAi() {
        when(queryService.gatherForTeam(TEAM_ID, LEADER_ID)).thenReturn(snapshot(List.of()));

        assertThat(service.recommendUsers(TEAM_ID, LEADER_ID, 10)).isEmpty();

        verifyNoInteractions(client, logService);
    }

    @Nested
    @DisplayName("AI 응답 정리")
    class ResponseSanitizing {

        @Test
        @DisplayName("점수 내림차순으로 다시 정렬한다")
        void sortsByScoreDesc() {
            givenCandidates(2L, 3L, 4L);
            givenAiResponse(item(3L, 0.4), item(4L, 0.95), item(2L, 0.7));
            givenDisplayInfo(2L, 3L, 4L);

            assertThat(service.recommendUsers(TEAM_ID, LEADER_ID, 10))
              .extracting(UserRecommendationResponseDTO::getScore)
              .containsExactly(0.95, 0.7, 0.4);
        }

        @Test
        @DisplayName("보낸 적 없는 candidate_id 와 점수 누락은 버린다")
        void dropsInvalidItems() {
            givenCandidates(2L);
            givenAiResponse(item(2L, 0.9), item(999L, 0.99), item(2L, null));
            givenDisplayInfo(2L);

            assertThat(service.recommendUsers(TEAM_ID, LEADER_ID, 10)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("limit 과 표시 정보 조회 순서")
    class LimitAndDisplayInfo {

        @Test
        @DisplayName("정렬 뒤에 자른다")
        void truncatesAfterSorting() {
            givenCandidates(2L, 3L, 4L);
            givenAiResponse(item(2L, 0.2), item(3L, 0.9), item(4L, 0.5));
            givenDisplayInfo(3L, 4L);

            assertThat(service.recommendUsers(TEAM_ID, LEADER_ID, 2))
              .extracting(UserRecommendationResponseDTO::getScore)
              .containsExactly(0.9, 0.5);
        }

        @Test
        @DisplayName("limit=0 이어도 1건은 준다")
        void zeroLimitReturnsOne() {
            givenCandidates(2L, 3L);
            givenAiResponse(item(2L, 0.9), item(3L, 0.5));
            givenDisplayInfo(2L);

            assertThat(service.recommendUsers(TEAM_ID, LEADER_ID, 0)).hasSize(1);
        }

        @Test
        @DisplayName("협업 온도는 상위 N 명 것만 조회한다")
        void loadsTemperatureOnlyForTop() {
            givenCandidates(2L, 3L, 4L);
            givenAiResponse(item(2L, 0.9), item(3L, 0.8), item(4L, 0.7));
            givenDisplayInfo(2L);

            service.recommendUsers(TEAM_ID, LEADER_ID, 1);

            ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
            verify(queryService).loadUserDisplayInfo(ids.capture(), any());
            assertThat(ids.getValue()).containsExactly(2L);
        }

        @Test
        @DisplayName("평가가 없는 유저도 온도가 비어 있지 않다 (프론트가 배지를 항상 그린다)")
        void temperatureIsAlwaysPresent() {
            givenCandidates(2L);
            givenAiResponse(item(2L, 0.9));
            Map<Long, UserDisplayInfo> info = new HashMap<>();
            info.put(2L, new UserDisplayInfo(user(2L), CollaborationTemperatureCalculator.INITIAL));
            when(queryService.loadUserDisplayInfo(anyList(), any())).thenReturn(info);

            assertThat(service.recommendUsers(TEAM_ID, LEADER_ID, 10).get(0)
              .getCollaborationTemperature())
              .isEqualByComparingTo(CollaborationTemperatureCalculator.INITIAL);
        }
    }

    @Nested
    @DisplayName("AI 요청 조립")
    class RequestBuilding {

        @Test
        @DisplayName("질의는 팀 메타데이터, 후보는 각 유저의 슬롯 값으로 채운다")
        void buildsRequest() {
            givenCandidates(2L);
            givenAiResponse(item(2L, 0.9));
            givenDisplayInfo(2L);

            service.recommendUsers(TEAM_ID, LEADER_ID, 10);

            ArgumentCaptor<TeamToUserRecommendationRequest> request
              = ArgumentCaptor.forClass(TeamToUserRecommendationRequest.class);
            verify(client).teamToUser(request.capture());

            assertThat(request.getValue().getQueryEmbeddingVector()).containsExactly(0.3f, 0.4f);
            assertThat(request.getValue().getQueryMetadata().getRecruitingRoles())
              .containsExactly("백엔드");
            assertThat(request.getValue().getQueryMetadata().getBeginnerFriendly()).isTrue();

            var candidate = request.getValue().getCandidates().get(0);
            assertThat(candidate.getCandidateId()).isEqualTo(2L);
            assertThat(candidate.getEmbeddingVector()).containsExactly(0.7f, 0.8f);
            assertThat(candidate.getMetadata().getSkills()).containsExactly("Figma");
        }
    }

    @Nested
    @DisplayName("기록")
    class Logging {

        @Test
        @DisplayName("자르기 전 후보 수를 기록에 넘긴다")
        void logsPreTruncationCandidateCount() {
            givenCandidates(2L, 3L, 4L);
            givenAiResponse(item(2L, 0.9), item(3L, 0.8), item(4L, 0.7));
            givenDisplayInfo(2L);

            service.recommendUsers(TEAM_ID, LEADER_ID, 1);

            verify(logService).saveTeamToUser(eq(TEAM_ID), eq(LEADER_ID), eq(3), eq(1), anyList());
        }

        @Test
        @DisplayName("기록 실패가 추천 응답을 막지 않는다")
        void loggingFailureIsSwallowed() {
            givenCandidates(2L);
            givenAiResponse(item(2L, 0.9));
            givenDisplayInfo(2L);
            doThrow(new RuntimeException("DB 다운"))
              .when(logService).saveTeamToUser(anyLong(), anyLong(), anyInt(), anyInt(), anyList());

            assertThatCode(() -> assertThat(service.recommendUsers(TEAM_ID, LEADER_ID, 10)).hasSize(1))
              .doesNotThrowAnyException();
        }
    }

    // --- 픽스처 -------------------------------------------------------------
    private void givenCandidates(Long... userIds) {
        List<UserRecommendationSnapshot.Candidate> candidates
          = List.of(userIds).stream().map(this::candidate).toList();
        when(queryService.gatherForTeam(anyLong(), anyLong())).thenReturn(snapshot(candidates));
    }

    private void givenAiResponse(RecommendationResponse.Recommendation... items) {
        RecommendationResponse response = new RecommendationResponse();
        response.setRecommendations(List.of(items));
        when(client.teamToUser(any())).thenReturn(response);
    }

    private void givenDisplayInfo(Long... userIds) {
        Map<Long, UserDisplayInfo> info = new HashMap<>();
        for (Long userId : userIds) {
            info.put(userId, new UserDisplayInfo(user(userId), new java.math.BigDecimal("38.5")));
        }
        when(queryService.loadUserDisplayInfo(anyList(), any())).thenReturn(info);
    }

    private UserRecommendationSnapshot snapshot(List<UserRecommendationSnapshot.Candidate> candidates) {
        return new UserRecommendationSnapshot(new float[]{0.3f, 0.4f},
          List.of("백엔드"), List.of("Spring"), "오프라인", true,
          "SCIENCE_ENGINEERING_TECH_IT", candidates);
    }

    private UserRecommendationSnapshot.Candidate candidate(Long userId) {
        User user = user(userId);
        MatchingIntentSlot slot = new MatchingIntentSlot(user);
        slot.update(null, List.of("디자이너"), List.of("Figma"), List.of("UX"),
          "포트폴리오", "온라인", "입문", "임베딩 원문");
        TestEntities.withId(slot, userId * 10);

        return new UserRecommendationSnapshot.Candidate(user, slot, new float[]{0.7f, 0.8f});
    }

    private User user(Long id) {
        return User.builder().id(id).name("사용자" + id).build();
    }

    private RecommendationResponse.Recommendation item(Long candidateId, Double score) {
        RecommendationResponse.Recommendation recommendation = new RecommendationResponse.Recommendation();
        recommendation.setCandidateId(candidateId);
        recommendation.setScore(score);
        recommendation.setLabel("근거 문구");
        return recommendation;
    }
}
