package com.example.mateon.teams.scheduler;

import com.example.mateon.teams.service.TeamCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 자동 종료 배치의 껍데기. 로직은 서비스에 있고, 여기서 지킬 것은 <b>스케줄러 스레드가 죽지
 * 않는 것</b> 하나다.
 *
 * <p>
 * {@code @Scheduled} 메서드가 예외를 밖으로 흘리면 해당 실행만 실패하는 게 아니라 로그에
 * 스택트레이스만 남고 조용히 지나간다 — 그리고 이 배치는 <b>협업 온도 평가가 열리는 유일한
 * 폴백</b>이다. 팀장 대부분이 프로젝트가 끝나면 앱을 열지 않으므로, 이게 멈추면 평가 데이터가
 * 사실상 쌓이지 않는데 어디에도 에러가 뜨지 않는다.
 *
 * <p>
 * try/catch 는 눈에 거슬려서 "서비스가 이미 트랜잭션이니 여기선 필요 없다"며 지워지기 쉽다.
 * 그래서 지우면 빨개지도록 테스트로 고정한다.
 */
class TeamCompletionSchedulerTest {

    private TeamCompletionService completionService;
    private TeamCompletionScheduler scheduler;

    @BeforeEach
    void setUp() {
        completionService = mock(TeamCompletionService.class);
        scheduler = new TeamCompletionScheduler(completionService);
    }

    @Test
    @DisplayName("오늘 날짜로 서비스를 부른다 (기준일은 스케줄러가 정한다)")
    void passesToday() {
        when(completionService.completeExpiredTeams(any())).thenReturn(0);

        scheduler.completeExpiredTeams();

        ArgumentCaptor<LocalDate> today = ArgumentCaptor.forClass(LocalDate.class);
        verify(completionService).completeExpiredTeams(today.capture());
        assertThat(today.getValue()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("서비스가 터져도 예외를 밖으로 내보내지 않는다 — 스케줄러가 계속 살아 있어야 한다")
    void swallowsServiceFailure() {
        when(completionService.completeExpiredTeams(any()))
          .thenThrow(new RuntimeException("DB 다운"));

        assertThatCode(() -> scheduler.completeExpiredTeams()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("종료된 팀이 있으면 그대로 정상 종료한다")
    void completedCountIsFine() {
        when(completionService.completeExpiredTeams(any())).thenReturn(3);

        assertThatCode(() -> scheduler.completeExpiredTeams()).doesNotThrowAnyException();

        verify(completionService).completeExpiredTeams(any());
    }

    @Test
    @DisplayName("종료된 팀이 없어도 정상 종료한다 (0건은 흔한 상태다)")
    void zeroIsFine() {
        when(completionService.completeExpiredTeams(any())).thenReturn(0);

        assertThatCode(() -> scheduler.completeExpiredTeams()).doesNotThrowAnyException();

        verify(completionService).completeExpiredTeams(any());
    }
}
