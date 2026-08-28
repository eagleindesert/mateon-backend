package com.example.mateon.matching.service;

import com.example.mateon.matching.client.recommendation.RecommendationResponse.Recommendation;
import com.example.mateon.matching.domain.SelectionDirection;
import com.example.mateon.matching.domain.TeamToUserRecommendationLog;
import com.example.mateon.matching.domain.UserToTeamRecommendationLog;
import com.example.mateon.matching.repository.TeamToUserRecommendationLogRepository;
import com.example.mateon.matching.repository.UserToTeamRecommendationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 추천 이력 기록.
 *
 * <p>
 * 단순한 저장처럼 보이지만 두 가지가 걸려 있다.
 *
 * <p>
 * <b>하나, 순위는 여기서 매긴다.</b> AI 가 준 배열 순서가 아니라 호출자가 정렬해 넘긴 순서로
 * 1 부터 채운다. 이 순위는 나중에 상세 이유의 {@code score_context} 로 쓰이고, 역제안에서는
 * 제안 발송 시 {@code team_offers} 에 스냅샷으로 복사된다 — 즉 화면 밖으로도 흘러간다.
 *
 * <p>
 * <b>둘, 이유 캐시 대상이 사라져도 예외가 아니다.</b> 로그 헤더가 지워지면 아이템도 cascade
 * 로 함께 사라진다. 그때 이유는 이미 생성돼 응답으로 나가는 중이라, 여기서 터지면 사용자는
 * 멀쩡히 만들어진 이유를 못 받는다.
 */
class RecommendationLogServiceTest {

    private UserToTeamRecommendationLogRepository logRepository;
    private TeamToUserRecommendationLogRepository teamToUserLogRepository;
    private RecommendationLogService service;

    @BeforeEach
    void setUp() {
        logRepository = mock(UserToTeamRecommendationLogRepository.class);
        teamToUserLogRepository = mock(TeamToUserRecommendationLogRepository.class);
        service = new RecommendationLogService(logRepository, teamToUserLogRepository);
    }

    @Nested
    @DisplayName("유저→팀 기록")
    class SaveUserToTeam {

        @Test
        @DisplayName("순위는 넘겨받은 순서대로 1 부터 매긴다 (AI 배열 순서가 아니다)")
        void ranksInGivenOrder() {
            service.save(1L, 7L, 50, 3, List.of(item(10L, 0.9), item(11L, 0.8), item(12L, 0.7)));

            UserToTeamRecommendationLog saved = captureUserToTeamLog();
            assertThat(items(saved)).hasSize(3);
            assertThat(rankNos(saved)).containsExactly(1, 2, 3);
            assertThat(teamIds(saved)).containsExactly(10L, 11L, 12L);
        }

        @Test
        @DisplayName("헤더에 후보 수와 활동 id 가 남는다 (나중에 상위 N 을 재검토할 근거)")
        void storesHeaderFields() {
            service.save(1L, 7L, 50, 1, List.of(item(10L, 0.9)));

            UserToTeamRecommendationLog saved = captureUserToTeamLog();
            assertThat(ReflectionTestUtils.getField(saved, "userId")).isEqualTo(1L);
            assertThat(ReflectionTestUtils.getField(saved, "eventId")).isEqualTo(7L);
            assertThat(ReflectionTestUtils.getField(saved, "candidateCount")).isEqualTo(50);
        }

        @Test
        @DisplayName("결과가 0건이어도 헤더는 남긴다 (추천을 시도했다는 사실 자체가 기록이다)")
        void savesEvenWithNoItems() {
            service.save(1L, null, 0, 0, List.of());

            assertThat(items(captureUserToTeamLog())).isEmpty();
        }
    }

    @Nested
    @DisplayName("팀→유저 기록")
    class SaveTeamToUser {

        @Test
        @DisplayName("같은 규칙으로 순위를 매기고 요청자 id 를 남긴다")
        void ranksAndStoresRequester() {
            service.saveTeamToUser(100L, 1L, 30, 2, List.of(item(2L, 0.9), item(3L, 0.5)));

            ArgumentCaptor<TeamToUserRecommendationLog> captor
              = ArgumentCaptor.forClass(TeamToUserRecommendationLog.class);
            verify(teamToUserLogRepository).save(captor.capture());

            TeamToUserRecommendationLog saved = captor.getValue();
            assertThat(ReflectionTestUtils.getField(saved, "teamId")).isEqualTo(100L);
            assertThat(ReflectionTestUtils.getField(saved, "requestedByUserId")).isEqualTo(1L);
            assertThat(ReflectionTestUtils.getField(saved, "candidateCount")).isEqualTo(30);
            assertThat(rankNos(saved)).containsExactly(1, 2);
        }

        @Test
        @DisplayName("유저→팀 저장소는 건드리지 않는다")
        void doesNotTouchOtherRepository() {
            service.saveTeamToUser(100L, 1L, 30, 1, List.of(item(2L, 0.9)));

            verify(logRepository, never()).save(org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested
    @DisplayName("상세 이유 캐시")
    class ReasonCache {

        @Test
        @DisplayName("유저→팀 이유는 해당 저장소로 간다")
        void userToTeamReason() {
            when(logRepository.updateReason(500L, "이유")).thenReturn(1);

            service.saveUserToTeamReason(500L, "이유");

            verify(logRepository).updateReason(500L, "이유");
            verify(teamToUserLogRepository, never()).updateReason(anyLong(), anyString());
        }

        @Test
        @DisplayName("팀→유저 이유는 반대쪽 저장소로 간다")
        void teamToUserReason() {
            when(teamToUserLogRepository.updateReason(600L, "이유")).thenReturn(1);

            service.saveTeamToUserReason(600L, "이유");

            verify(teamToUserLogRepository).updateReason(600L, "이유");
            verify(logRepository, never()).updateReason(anyLong(), anyString());
        }

        @Test
        @DisplayName("갱신 대상이 사라졌어도(0건) 예외를 던지지 않는다 — 이유는 이미 응답으로 나가는 중이다")
        void missingRowIsNotAnError() {
            when(logRepository.updateReason(anyLong(), anyString())).thenReturn(0);
            when(teamToUserLogRepository.updateReason(anyLong(), anyString())).thenReturn(0);

            assertThatCode(() -> {
                service.saveUserToTeamReason(500L, "이유");
                service.saveTeamToUserReason(600L, "이유");
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("컴포넌트 점수 보관 — 선택 피드백으로 그대로 되돌려줄 값이다")
    class ComponentScores {

        @Test
        @DisplayName("AI 가 준 JSON 을 문자 그대로 저장한다 (키 이름/순서를 바꾸지 않는다)")
        void storesRawJson() {
            String raw = "{\"similarity\":0.8,\"role_match\":1.0}";
            service.save(1L, null, 1, 1, List.of(withComponentScores(10L, 0.9, raw)));

            assertThat(componentScores(captureUserToTeamLog())).containsExactly(raw);
        }

        @Test
        @DisplayName("AI 가 안 주면 null 로 남긴다 — 우리가 지어낼 수 있는 값이 아니다")
        void missingScoresBecomeNull() {
            service.save(1L, null, 1, 1, List.of(item(10L, 0.9)));

            assertThat(componentScores(captureUserToTeamLog())).containsExactly((String) null);
        }

        @Test
        @DisplayName("역제안도 같은 규칙이다 (한쪽만 고치는 사고 방지)")
        void teamToUserStoresRawJsonToo() {
            String raw = "{\"similarity\":0.77}";
            service.saveTeamToUser(100L, 1L, 1, 1, List.of(withComponentScores(2L, 0.9, raw)));

            ArgumentCaptor<TeamToUserRecommendationLog> captor
              = ArgumentCaptor.forClass(TeamToUserRecommendationLog.class);
            verify(teamToUserLogRepository).save(captor.capture());
            assertThat(componentScores(captor.getValue())).containsExactly(raw);
        }
    }

    @Nested
    @DisplayName("노출 건수")
    class ShownCount {

        @Test
        @DisplayName("점수화된 전체가 아니라 프론트에 내려간 건수를 남긴다")
        void recordsShownCountSeparately() {
            service.save(1L, null, 50, 2,
              List.of(item(10L, 0.9), item(11L, 0.8), item(12L, 0.7)));

            UserToTeamRecommendationLog saved = captureUserToTeamLog();
            assertThat(ReflectionTestUtils.getField(saved, "candidateCount")).isEqualTo(50);
            assertThat(ReflectionTestUtils.getField(saved, "shownCount")).isEqualTo(2);
            // 아이템은 여전히 전체가 남는다 — 자르는 건 전송 시점이다.
            assertThat(items(saved)).hasSize(3);
        }
    }

    @Nested
    @DisplayName("선택 표시")
    class MarkSelected {

        @Test
        @DisplayName("방향에 맞는 저장소로만 간다")
        void routesByDirection() {
            when(logRepository.markSelected(anyLong(), any())).thenReturn(1);

            service.markSelected(SelectionDirection.USER_TO_TEAM, 900L);

            verify(logRepository).markSelected(eq(900L), any());
            verify(teamToUserLogRepository, never()).markSelected(anyLong(), any());
        }

        @Test
        @DisplayName("역제안은 반대쪽 저장소로 간다")
        void routesTeamToUser() {
            when(teamToUserLogRepository.markSelected(anyLong(), any())).thenReturn(1);

            service.markSelected(SelectionDirection.TEAM_TO_USER, 901L);

            verify(teamToUserLogRepository).markSelected(eq(901L), any());
            verify(logRepository, never()).markSelected(anyLong(), any());
        }

        @Test
        @DisplayName("대상이 사라졌어도(0건) 예외를 던지지 않는다 — 지원/제안은 이미 성공했다")
        void missingRowIsNotAnError() {
            when(logRepository.markSelected(anyLong(), any())).thenReturn(0);

            assertThatCode(() -> service.markSelected(SelectionDirection.USER_TO_TEAM, 900L))
              .doesNotThrowAnyException();
        }
    }

    // --- 헬퍼 ---------------------------------------------------------------
    private UserToTeamRecommendationLog captureUserToTeamLog() {
        ArgumentCaptor<UserToTeamRecommendationLog> captor
          = ArgumentCaptor.forClass(UserToTeamRecommendationLog.class);
        verify(logRepository).save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<Object> items(Object log) {
        return (List<Object>) ReflectionTestUtils.getField(log, "items");
    }

    private List<Integer> rankNos(Object log) {
        return items(log).stream()
          .map(item -> (Integer) ReflectionTestUtils.getField(item, "rankNo"))
          .toList();
    }

    private List<Long> teamIds(Object log) {
        return items(log).stream()
          .map(item -> (Long) ReflectionTestUtils.getField(item, "teamId"))
          .toList();
    }

    private Recommendation item(Long candidateId, Double score) {
        Recommendation recommendation = new Recommendation();
        recommendation.setCandidateId(candidateId);
        recommendation.setScore(score);
        recommendation.setLabel("근거");
        return recommendation;
    }

    /**
     * AI 응답을 흉내 내려면 JsonNode 로 넣어야 한다 — 문자열을 직접 세팅하면 실제 역직렬화
     * 경로를 건너뛰어 "원문 그대로인가"를 검증하지 못한다.
     *
     * <p>
     * 매퍼는 Jackson 3 이어야 한다. {@code aiRestTemplate} 에서 이기는 컨버터가 Jackson 3 이라
     * 운영에서 이 필드에 들어오는 노드도 Jackson 3 의 것이다 (RecommendationResponse 주석 참고).
     */
    private Recommendation withComponentScores(Long candidateId, Double score, String rawJson) {
        Recommendation recommendation = item(candidateId, score);
        recommendation.setComponentScores(new ObjectMapper().readTree(rawJson));
        return recommendation;
    }

    private List<String> componentScores(Object log) {
        return items(log).stream()
          .map(item -> (String) ReflectionTestUtils.getField(item, "componentScores"))
          .toList();
    }
}
