package com.example.mateon.matching.service;

import com.example.mateon.matching.client.recommendation.RecommendationClient;
import com.example.mateon.matching.client.recommendation.RecommendationReasonRequest;
import com.example.mateon.matching.dto.snapshot.ReasonSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 상세 이유 생성의 캐시 규약과 <b>방향별 저장 위치</b>를 고정한다.
 *
 * <p>
 * 여기서 제일 위험한 건 두 방향이 {@code BiConsumer} 메서드 참조로만 갈린다는 점이다 —
 * {@code logService::saveUserToTeamReason} 과 {@code logService::saveTeamToUserReason} 은
 * 시그니처가 같아서 서로 바꿔 써도 컴파일된다. 바뀌면 유저→팀 이유가 역제안 테이블에 저장되고,
 * 사용자는 "이유를 만들 때마다 매번 새로 생성된다"(캐시가 영원히 안 맞는다)는 증상만 겪는다.
 * LLM 호출이 매번 나가므로 비용도 계속 샌다.
 *
 * <p>
 * 두 번째는 <b>캐시 hit 시 AI 를 부르지 않는 것</b>. 이 조기 반환이 사라져도 결과 문자열은
 * 같기 때문에 눈으로는 알 수 없고 요금으로만 드러난다.
 */
class RecommendationReasonServiceTest {

    private static final long USER_ID = 1L;
    private static final long TEAM_ID = 100L;
    private static final long TARGET_USER_ID = 2L;
    private static final long ITEM_ID = 500L;

    private RecommendationQueryService queryService;
    private RecommendationLogService logService;
    private RecommendationClient client;
    private RecommendationReasonService service;

    @BeforeEach
    void setUp() {
        queryService = mock(RecommendationQueryService.class);
        logService = mock(RecommendationLogService.class);
        client = mock(RecommendationClient.class);
        service = new RecommendationReasonService(queryService, logService, client);
    }

    @Nested
    @DisplayName("캐시")
    class Caching {

        @Test
        @DisplayName("캐시가 있으면 AI 를 부르지도, 다시 저장하지도 않는다")
        void cacheHitSkipsAiAndWrite() {
            when(queryService.gatherReasonForUserToTeam(USER_ID, TEAM_ID))
              .thenReturn(cached("이미 만들어 둔 이유"));

            assertThat(service.explainTeam(USER_ID, TEAM_ID)).isEqualTo("이미 만들어 둔 이유");

            verifyNoInteractions(client, logService);
        }

        @Test
        @DisplayName("캐시가 없으면 AI 를 부르고 그 결과를 캐시한다")
        void cacheMissCallsAiAndWrites() {
            when(queryService.gatherReasonForUserToTeam(USER_ID, TEAM_ID)).thenReturn(fresh());
            when(client.reason(any())).thenReturn("새로 만든 이유");

            assertThat(service.explainTeam(USER_ID, TEAM_ID)).isEqualTo("새로 만든 이유");

            verify(logService).saveUserToTeamReason(ITEM_ID, "새로 만든 이유");
        }

        @Test
        @DisplayName("캐시 저장이 실패해도 이유는 그대로 돌려준다 (이미 LLM 비용을 치렀다)")
        void cacheWriteFailureIsSwallowed() {
            when(queryService.gatherReasonForUserToTeam(USER_ID, TEAM_ID)).thenReturn(fresh());
            when(client.reason(any())).thenReturn("새로 만든 이유");
            doThrow(new RuntimeException("DB 다운"))
              .when(logService).saveUserToTeamReason(anyLong(), anyString());

            assertThatCode(() -> assertThat(service.explainTeam(USER_ID, TEAM_ID))
              .isEqualTo("새로 만든 이유"))
              .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("방향 — 캐시를 엉뚱한 테이블에 쓰면 영영 hit 하지 않는다")
    class Direction {

        @Test
        @DisplayName("유저→팀 이유는 user_to_team 쪽에만 캐시한다")
        void userToTeamWritesToItsOwnTable() {
            when(queryService.gatherReasonForUserToTeam(USER_ID, TEAM_ID)).thenReturn(fresh());
            when(client.reason(any())).thenReturn("이유");

            service.explainTeam(USER_ID, TEAM_ID);

            verify(logService).saveUserToTeamReason(ITEM_ID, "이유");
            verify(logService, never()).saveTeamToUserReason(anyLong(), anyString());
        }

        @Test
        @DisplayName("팀→유저 이유는 team_to_user 쪽에만 캐시한다")
        void teamToUserWritesToItsOwnTable() {
            when(queryService.gatherReasonForTeamToUser(TEAM_ID, TARGET_USER_ID, USER_ID))
              .thenReturn(fresh());
            when(client.reason(any())).thenReturn("이유");

            service.explainUser(TEAM_ID, TARGET_USER_ID, USER_ID);

            verify(logService).saveTeamToUserReason(ITEM_ID, "이유");
            verify(logService, never()).saveUserToTeamReason(anyLong(), anyString());
        }

        @Test
        @DisplayName("팀→유저 조회에는 요청자(팀장) id 가 함께 넘어간다 (권한 검사의 근거)")
        void teamToUserPassesRequesterForAuthorization() {
            when(queryService.gatherReasonForTeamToUser(TEAM_ID, TARGET_USER_ID, USER_ID))
              .thenReturn(cached("이유"));

            service.explainUser(TEAM_ID, TARGET_USER_ID, USER_ID);

            verify(queryService).gatherReasonForTeamToUser(TEAM_ID, TARGET_USER_ID, USER_ID);
        }
    }

    @Test
    @DisplayName("AI 요청에는 후보/대상 요약과 점수 서술이 스냅샷 그대로 실린다")
    void carriesSnapshotIntoRequest() {
        when(queryService.gatherReasonForUserToTeam(USER_ID, TEAM_ID)).thenReturn(fresh());
        when(client.reason(any())).thenReturn("이유");

        service.explainTeam(USER_ID, TEAM_ID);

        ArgumentCaptor<RecommendationReasonRequest> request
          = ArgumentCaptor.forClass(RecommendationReasonRequest.class);
        verify(client).reason(request.capture());

        assertThat(request.getValue().getCandidateSummary()).isEqualTo("후보 요약");
        assertThat(request.getValue().getTargetSummary()).isEqualTo("대상 요약");
        assertThat(request.getValue().getScoreContext()).isEqualTo("점수 0.9, 1위");
    }

    // --- 픽스처 -------------------------------------------------------------
    private ReasonSnapshot cached(String reason) {
        return new ReasonSnapshot(ITEM_ID, null, null, null, reason);
    }

    private ReasonSnapshot fresh() {
        return new ReasonSnapshot(ITEM_ID, "후보 요약", "대상 요약", "점수 0.9, 1위", null);
    }
}
