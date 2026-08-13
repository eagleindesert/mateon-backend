package com.example.mateon.matching.service;

import com.example.mateon.matching.client.RecommendationClient;
import com.example.mateon.matching.client.RecommendationResponse;
import com.example.mateon.matching.client.UserToTeamRecommendationRequest;
import com.example.mateon.matching.dto.response.TeamRecommendationResponseDTO;
import com.example.mateon.matching.dto.snapshot.RecommendationSnapshot;
import com.example.mateon.matching.dto.snapshot.TeamDisplayInfo;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamEmbedding;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 유저→팀 추천 오케스트레이션.
 *
 * <p>여기서 지키는 규칙은 전부 <b>"AI 를 신뢰하지 않는다"</b>로 요약된다. AI 서버는 외부
 * 프로세스라 순서를 보장하지 않고, 우리가 보낸 적 없는 id 를 돌려줄 수도 있으며, 점수를
 * 빠뜨릴 수도 있다. 그래서 응답이 오는 대로 쓰지 않고 다시 거르고 다시 정렬한다.
 * 이 필터·정렬이 사라져도 대부분의 경우 결과가 그럴듯해서, 테스트 없이는 알 수 없다.
 *
 * <p>또 하나는 <b>순서에서 오는 비용</b>이다. 표시 정보(활동·인원)는 상위 N 건을 자른 <i>뒤에</i>
 * 조회해야 한다. 자르기를 뒤에 두면 후보 200개를 전부 조회해 10개만 쓰게 된다 — 결과는 같고
 * 쿼리만 20배가 되는, 눈에 보이지 않는 회귀다.
 *
 * <p>마지막으로 <b>기록 실패가 추천을 죽이지 않는다</b>. 추천은 이미 성공했고 사용자가 기다린
 * LLM 호출도 끝났는데, 로그 저장이 안 됐다고 응답을 버리는 건 손해가 크다.
 */
class RecommendationServiceTest {

    private static final long USER_ID = 1L;

    private RecommendationQueryService queryService;
    private RecommendationLogService logService;
    private RecommendationClient client;
    private RecommendationService service;

    @BeforeEach
    void setUp() {
        queryService = mock(RecommendationQueryService.class);
        logService = mock(RecommendationLogService.class);
        client = mock(RecommendationClient.class);
        service = new RecommendationService(queryService, logService, client);
    }

    @Nested
    @DisplayName("후보가 없을 때")
    class NoCandidates {

        @Test
        @DisplayName("AI 를 아예 부르지 않는다 — 모집 중인 팀이 없는 건 정상 상태다 (LLM 비용도 든다)")
        void doesNotCallAi() {
            when(queryService.gather(USER_ID, null)).thenReturn(snapshot(List.of()));

            assertThat(service.recommendTeams(USER_ID, null, 10)).isEmpty();

            verifyNoInteractions(client, logService);
        }
    }

    @Nested
    @DisplayName("AI 응답 정리 — 외부 입력은 신뢰하지 않는다")
    class ResponseSanitizing {

        @Test
        @DisplayName("AI 가 어떤 순서로 주든 점수 내림차순으로 다시 정렬한다")
        void alwaysSortsByScoreDesc() {
            givenCandidates(10L, 11L, 12L);
            givenAiResponse(item(11L, 0.5), item(12L, 0.9), item(10L, 0.7));
            givenDisplayInfo(10L, 11L, 12L);

            assertThat(service.recommendTeams(USER_ID, null, 10))
                    .extracting(TeamRecommendationResponseDTO::getScore)
                    .containsExactly(0.9, 0.7, 0.5);
        }

        @Test
        @DisplayName("보낸 적 없는 candidate_id 는 조용히 버린다 (예외를 던지면 멀쩡한 추천까지 죽는다)")
        void dropsUnknownCandidateId() {
            givenCandidates(10L);
            givenAiResponse(item(10L, 0.9), item(999L, 0.99));
            givenDisplayInfo(10L);

            assertThat(service.recommendTeams(USER_ID, null, 10)).hasSize(1);
        }

        @Test
        @DisplayName("점수가 빠진 항목도 버린다")
        void dropsNullScore() {
            givenCandidates(10L, 11L);
            givenAiResponse(item(10L, 0.9), item(11L, null));
            givenDisplayInfo(10L, 11L);

            assertThat(service.recommendTeams(USER_ID, null, 10)).hasSize(1);
        }

        @Test
        @DisplayName("candidate_id 가 null 이어도 버린다")
        void dropsNullCandidateId() {
            givenCandidates(10L);
            givenAiResponse(item(10L, 0.9), item(null, 0.8));
            givenDisplayInfo(10L);

            assertThat(service.recommendTeams(USER_ID, null, 10)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("limit — 정렬 뒤에 자른다")
    class Limit {

        @Test
        @DisplayName("점수 상위부터 limit 만큼만 남는다 (자르고 정렬하면 상위가 아닌 것이 남는다)")
        void truncatesAfterSorting() {
            givenCandidates(10L, 11L, 12L);
            givenAiResponse(item(10L, 0.3), item(11L, 0.9), item(12L, 0.6));
            givenDisplayInfo(11L, 12L);

            assertThat(service.recommendTeams(USER_ID, null, 2))
                    .extracting(TeamRecommendationResponseDTO::getScore)
                    .containsExactly(0.9, 0.6);
        }

        @Test
        @DisplayName("limit=0 이어도 최소 1건은 준다 (Math.max(limit,1))")
        void zeroLimitStillReturnsOne() {
            givenCandidates(10L, 11L);
            givenAiResponse(item(10L, 0.9), item(11L, 0.5));
            givenDisplayInfo(10L);

            assertThat(service.recommendTeams(USER_ID, null, 0)).hasSize(1);
        }

        @Test
        @DisplayName("표시 정보는 잘라낸 뒤 상위 id 만으로 조회한다 (후보 전체를 조회하면 쿼리가 헛돈다)")
        void loadsDisplayInfoOnlyForTopResults() {
            givenCandidates(10L, 11L, 12L);
            givenAiResponse(item(10L, 0.9), item(11L, 0.8), item(12L, 0.7));
            givenDisplayInfo(10L);

            service.recommendTeams(USER_ID, null, 1);

            ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
            verify(queryService).loadDisplayInfo(ids.capture(), any());
            assertThat(ids.getValue()).containsExactly(10L);
        }
    }

    @Nested
    @DisplayName("AI 요청 조립")
    class RequestBuilding {

        @Test
        @DisplayName("팀 메타데이터는 teams 원본이 아니라 team_embeddings 의 AI 정규화 값을 쓴다")
        void usesNormalizedTeamMetadata() {
            RecommendationSnapshot snapshot = snapshot(List.of(candidateWithMetadata(10L)));
            when(queryService.gather(USER_ID, null)).thenReturn(snapshot);
            givenAiResponse(item(10L, 0.9));
            givenDisplayInfo(10L);

            service.recommendTeams(USER_ID, null, 10);

            ArgumentCaptor<UserToTeamRecommendationRequest> request =
                    ArgumentCaptor.forClass(UserToTeamRecommendationRequest.class);
            verify(client).userToTeam(request.capture());

            var candidate = request.getValue().getCandidates().get(0);
            assertThat(candidate.getCandidateId()).isEqualTo(10L);
            assertThat(candidate.getMetadata().getRecruitingRoles()).containsExactly("백엔드-정규화");
            assertThat(candidate.getMetadata().getRequiredSkills()).containsExactly("Spring-정규화");
            assertThat(candidate.getMetadata().getBeginnerFriendly()).isTrue();
        }

        @Test
        @DisplayName("질의 메타데이터는 슬롯 값을 그대로 싣는다")
        void carriesQueryMetadata() {
            givenCandidates(10L);
            givenAiResponse(item(10L, 0.9));
            givenDisplayInfo(10L);

            service.recommendTeams(USER_ID, null, 10);

            ArgumentCaptor<UserToTeamRecommendationRequest> request =
                    ArgumentCaptor.forClass(UserToTeamRecommendationRequest.class);
            verify(client).userToTeam(request.capture());

            assertThat(request.getValue().getQueryEmbeddingVector()).containsExactly(0.5f, 0.5f);
            assertThat(request.getValue().getQueryMetadata().getDesiredRoles()).containsExactly("디자이너");
            assertThat(request.getValue().getQueryMetadata().getExperienceLevel()).isEqualTo("입문");
        }
    }

    @Nested
    @DisplayName("기록")
    class Logging {

        @Test
        @DisplayName("기록에는 자르기 전 후보 수와 정렬된 전체 결과를 넘긴다")
        void logsPreTruncationCounts() {
            givenCandidates(10L, 11L, 12L);
            givenAiResponse(item(10L, 0.9), item(11L, 0.8), item(12L, 0.7));
            givenDisplayInfo(10L);

            service.recommendTeams(USER_ID, null, 1);

            ArgumentCaptor<List<RecommendationResponse.Recommendation>> ranked =
                    ArgumentCaptor.forClass(List.class);
            verify(logService).save(anyLong(), any(), org.mockito.ArgumentMatchers.eq(3), ranked.capture());
            assertThat(ranked.getValue()).hasSize(3);
        }

        @Test
        @DisplayName("기록이 실패해도 추천 응답은 그대로 나간다 (LLM 비용을 이미 치렀다)")
        void loggingFailureDoesNotBreakResponse() {
            givenCandidates(10L);
            givenAiResponse(item(10L, 0.9));
            givenDisplayInfo(10L);
            doThrow(new RuntimeException("DB 다운"))
                    .when(logService).save(anyLong(), any(), anyInt(), anyList());

            assertThatCode(() -> assertThat(service.recommendTeams(USER_ID, null, 10)).hasSize(1))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("eventId 는 조회에 그대로 전달된다 (활동별 추천)")
    void passesEventId() {
        when(queryService.gather(USER_ID, 7L)).thenReturn(snapshot(List.of()));

        service.recommendTeams(USER_ID, 7L, 10);

        verify(queryService).gather(USER_ID, 7L);
        verify(queryService, never()).gather(USER_ID, null);
    }

    // --- 픽스처 -------------------------------------------------------------

    private void givenCandidates(Long... teamIds) {
        List<RecommendationSnapshot.Candidate> candidates =
                List.of(teamIds).stream().map(this::candidate).toList();
        when(queryService.gather(anyLong(), any())).thenReturn(snapshot(candidates));
    }

    private void givenAiResponse(RecommendationResponse.Recommendation... items) {
        RecommendationResponse response = new RecommendationResponse();
        response.setRecommendations(List.of(items));
        when(client.userToTeam(any())).thenReturn(response);
    }

    private void givenDisplayInfo(Long... teamIds) {
        Map<Long, TeamDisplayInfo> info = new HashMap<>();
        for (Long teamId : teamIds) {
            info.put(teamId, new TeamDisplayInfo(null, 2));
        }
        when(queryService.loadDisplayInfo(anyList(), any())).thenReturn(info);
    }

    private RecommendationSnapshot snapshot(List<RecommendationSnapshot.Candidate> candidates) {
        return new RecommendationSnapshot(new float[]{0.5f, 0.5f},
                List.of("디자이너"), List.of("Figma"), "온라인", "입문", candidates);
    }

    private RecommendationSnapshot.Candidate candidate(Long teamId) {
        Team team = new Team();
        team.setId(teamId);
        team.setTitle("팀 " + teamId);

        TeamEmbedding embedding = new TeamEmbedding();
        embedding.setTeamId(teamId);
        embedding.setEmbedding(new float[]{0.1f, 0.2f});

        return new RecommendationSnapshot.Candidate(team, embedding);
    }

    /** team_embeddings 의 정규화 값과 teams 원본이 다른 상황을 만든다. */
    private RecommendationSnapshot.Candidate candidateWithMetadata(Long teamId) {
        Team team = new Team();
        team.setId(teamId);
        team.setTitle("팀 " + teamId);
        team.setRole(List.of("백엔드-원본"));
        team.setRequiredSkills(List.of("Spring-원본"));

        TeamEmbedding embedding = new TeamEmbedding();
        embedding.setTeamId(teamId);
        embedding.setEmbedding(new float[]{0.1f, 0.2f});
        embedding.setRecruitingRoles(List.of("백엔드-정규화"));
        embedding.setRequiredSkills(List.of("Spring-정규화"));
        embedding.setActivityStyle("온라인");
        embedding.setBeginnerFriendly(true);

        return new RecommendationSnapshot.Candidate(team, embedding);
    }

    private RecommendationResponse.Recommendation item(Long candidateId, Double score) {
        RecommendationResponse.Recommendation recommendation = new RecommendationResponse.Recommendation();
        recommendation.setCandidateId(candidateId);
        recommendation.setScore(score);
        recommendation.setLabel("근거 문구");
        return recommendation;
    }
}
