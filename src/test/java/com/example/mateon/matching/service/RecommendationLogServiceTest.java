package com.example.mateon.matching.service;

import com.example.mateon.matching.client.recommendation.RecommendationResponse.Recommendation;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 추천 이력 기록.
 *
 * <p>단순한 저장처럼 보이지만 두 가지가 걸려 있다.
 *
 * <p><b>하나, 순위는 여기서 매긴다.</b> AI 가 준 배열 순서가 아니라 호출자가 정렬해 넘긴 순서로
 * 1 부터 채운다. 이 순위는 나중에 상세 이유의 {@code score_context} 로 쓰이고, 역제안에서는
 * 제안 발송 시 {@code team_offers} 에 스냅샷으로 복사된다 — 즉 화면 밖으로도 흘러간다.
 *
 * <p><b>둘, 이유 캐시 대상이 사라져도 예외가 아니다.</b> 로그 헤더가 지워지면 아이템도 cascade
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
            service.save(1L, 7L, 50, List.of(item(10L, 0.9), item(11L, 0.8), item(12L, 0.7)));

            UserToTeamRecommendationLog saved = captureUserToTeamLog();
            assertThat(items(saved)).hasSize(3);
            assertThat(rankNos(saved)).containsExactly(1, 2, 3);
            assertThat(teamIds(saved)).containsExactly(10L, 11L, 12L);
        }

        @Test
        @DisplayName("헤더에 후보 수와 활동 id 가 남는다 (나중에 상위 N 을 재검토할 근거)")
        void storesHeaderFields() {
            service.save(1L, 7L, 50, List.of(item(10L, 0.9)));

            UserToTeamRecommendationLog saved = captureUserToTeamLog();
            assertThat(ReflectionTestUtils.getField(saved, "userId")).isEqualTo(1L);
            assertThat(ReflectionTestUtils.getField(saved, "eventId")).isEqualTo(7L);
            assertThat(ReflectionTestUtils.getField(saved, "candidateCount")).isEqualTo(50);
        }

        @Test
        @DisplayName("결과가 0건이어도 헤더는 남긴다 (추천을 시도했다는 사실 자체가 기록이다)")
        void savesEvenWithNoItems() {
            service.save(1L, null, 0, List.of());

            assertThat(items(captureUserToTeamLog())).isEmpty();
        }
    }

    @Nested
    @DisplayName("팀→유저 기록")
    class SaveTeamToUser {

        @Test
        @DisplayName("같은 규칙으로 순위를 매기고 요청자 id 를 남긴다")
        void ranksAndStoresRequester() {
            service.saveTeamToUser(100L, 1L, 30, List.of(item(2L, 0.9), item(3L, 0.5)));

            ArgumentCaptor<TeamToUserRecommendationLog> captor =
                    ArgumentCaptor.forClass(TeamToUserRecommendationLog.class);
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
            service.saveTeamToUser(100L, 1L, 30, List.of(item(2L, 0.9)));

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

    // --- 헬퍼 ---------------------------------------------------------------

    private UserToTeamRecommendationLog captureUserToTeamLog() {
        ArgumentCaptor<UserToTeamRecommendationLog> captor =
                ArgumentCaptor.forClass(UserToTeamRecommendationLog.class);
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
}
