package com.example.mateon.teams.event;

import com.example.mateon.teams.service.TeamEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 팀 임베딩 갱신 리스너의 실패 삼킴을 고정한다.
 *
 * <p>
 * 운영 경로는 {@code @Async} 라 통합 테스트에서 기다리지 않는다. 여기서 직접 호출해
 * 성공 위임과, AI 장애가 팀 CRUD 를 막지 않는지를 본다.
 */
class TeamEmbeddingRefreshListenerTest {

    private static final long TEAM_ID = 7L;

    private TeamEmbeddingService teamEmbeddingService;
    private TeamEmbeddingRefreshListener listener;

    @BeforeEach
    void setUp() {
        teamEmbeddingService = mock(TeamEmbeddingService.class);
        listener = new TeamEmbeddingRefreshListener(teamEmbeddingService);
    }

    @Test
    @DisplayName("커밋 후 이벤트의 teamId 로 갱신을 위임한다")
    void delegatesRefresh() {
        listener.onRefreshRequested(new TeamEmbeddingRefreshRequestedEvent(TEAM_ID));

        verify(teamEmbeddingService).refresh(TEAM_ID);
    }

    @Test
    @DisplayName("갱신이 터져도 예외를 밖으로 내보내지 않는다")
    void swallowsRefreshFailure() {
        doThrow(new RuntimeException("AI 다운")).when(teamEmbeddingService).refresh(TEAM_ID);

        assertThatCode(() -> listener.onRefreshRequested(
          new TeamEmbeddingRefreshRequestedEvent(TEAM_ID)))
          .doesNotThrowAnyException();
    }
}
