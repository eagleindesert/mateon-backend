package com.example.mateon.teams.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.event.TeamCompletedEvent;
import com.example.mateon.teams.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 팀 활동 종료 — <b>협업 온도 평가가 열리는 유일한 관문</b>이다.
 *
 * <p>
 * 그래서 여기서 이벤트가 안 나가면 팀원 전원이 평가 요청 알림을 못 받고, 평가 기간
 * (종료 시각 + N일)은 아무도 모르는 채로 흘러가 닫힌다. 되돌릴 방법은 없다 — 팀은 이미
 * 종료돼 있어서 다시 종료할 수도 없다({@code TEAM_ALREADY_ENDED}). 화면상으로는 "종료됨"으로
 * 잘 보이므로 신고도 안 들어온다.
 *
 * <p>
 * {@code autoCompleted} 플래그는 알림 문구를 가르는 값인데, 스케줄러 경로에서만 true 라
 * 수동 테스트로는 절대 확인되지 않는다. 두 경로가 각각 어떤 값을 싣는지 여기서 못박는다.
 *
 * <p>
 * 종료가 {@code isRecruiting=false} 를 함께 세우는 것도 계약이다 — 수동 종료는 모집 중인
 * 팀에도 걸리므로, 이게 빠지면 "끝난 팀이 모집 목록에 계속 떠 있는" 상태가 된다.
 */
class TeamCompletionServiceTest {

    private static final long TEAM_ID = 7L;
    private static final long LEADER_ID = 1L;

    private TeamRepository teamRepository;
    private ApplicationEventPublisher eventPublisher;
    private TeamCompletionService service;

    @BeforeEach
    void setUp() {
        teamRepository = mock(TeamRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new TeamCompletionService(teamRepository, eventPublisher);
    }

    @Nested
    @DisplayName("팀장 수동 종료")
    class ByLeader {

        @Test
        @DisplayName("endedAt 이 찍히고 모집이 닫히고 이벤트가 나간다 (세 가지가 함께다)")
        void completesTeam() {
            Team team = givenTeam();

            service.completeByLeader(TEAM_ID, LEADER_ID);

            assertThat(team.getEndedAt()).isNotNull();
            assertThat(team.isEnded()).isTrue();
            assertThat(team.getIsRecruiting()).isFalse();
            verify(eventPublisher).publishEvent(any(TeamCompletedEvent.class));
        }

        @Test
        @DisplayName("수동 종료의 autoCompleted 는 false 다 (알림 문구가 갈린다)")
        void flagIsFalse() {
            givenTeam();

            service.completeByLeader(TEAM_ID, LEADER_ID);

            assertThat(capturedEvents(1).get(0).autoCompleted()).isFalse();
        }

        @Test
        @DisplayName("팀장이 아니면 종료할 수 없다")
        void nonLeaderRejected() {
            givenTeam();

            assertThatThrownBy(() -> service.completeByLeader(TEAM_ID, 2L))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN_ACCESS);

            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("이미 종료된 팀은 다시 종료할 수 없다 — 평가 기간이 연장되면 안 된다")
        void alreadyEnded() {
            Team team = givenTeam();
            LocalDateTime originalEndedAt = LocalDateTime.now().minusDays(10);
            team.setEndedAt(originalEndedAt);

            assertThatThrownBy(() -> service.completeByLeader(TEAM_ID, LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.TEAM_ALREADY_ENDED);

            assertThat(team.getEndedAt()).isEqualTo(originalEndedAt);
            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("없는 팀은 RESOURCE_NOT_FOUND 다")
        void missingTeam() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.completeByLeader(TEAM_ID, LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("마감일 경과 자동 종료")
    class Expired {

        @Test
        @DisplayName("팀마다 이벤트가 정확히 한 건씩 나간다")
        void oneEventPerTeam() {
            when(teamRepository.findEndedEventTeamsNotCompleted(any()))
              .thenReturn(List.of(team(10L), team(11L), team(12L)));

            assertThat(service.completeExpiredTeams(LocalDate.of(2026, 8, 14))).isEqualTo(3);

            assertThat(capturedEvents(3))
              .extracting(TeamCompletedEvent::teamId)
              .containsExactly(10L, 11L, 12L);
        }

        @Test
        @DisplayName("자동 종료의 autoCompleted 는 true 다 — 이 경로로만 true 가 될 수 있다")
        void flagIsTrue() {
            when(teamRepository.findEndedEventTeamsNotCompleted(any()))
              .thenReturn(List.of(team(10L)));

            service.completeExpiredTeams(LocalDate.of(2026, 8, 14));

            assertThat(capturedEvents(1).get(0).autoCompleted()).isTrue();
        }

        @Test
        @DisplayName("오늘 날짜가 그대로 쿼리에 넘어간다 (기준일을 서비스가 다시 만들지 않는다)")
        void passesTodayThrough() {
            LocalDate today = LocalDate.of(2026, 8, 14);
            when(teamRepository.findEndedEventTeamsNotCompleted(today)).thenReturn(List.of());

            service.completeExpiredTeams(today);

            verify(teamRepository).findEndedEventTeamsNotCompleted(today);
        }

        @Test
        @DisplayName("대상이 없으면 0 을 돌려주고 아무 이벤트도 내지 않는다")
        void noneExpired() {
            when(teamRepository.findEndedEventTeamsNotCompleted(any())).thenReturn(List.of());

            assertThat(service.completeExpiredTeams(LocalDate.of(2026, 8, 14))).isZero();

            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("자동 종료도 모집을 닫는다")
        void closesRecruiting() {
            Team team = team(10L);
            when(teamRepository.findEndedEventTeamsNotCompleted(any())).thenReturn(List.of(team));

            service.completeExpiredTeams(LocalDate.of(2026, 8, 14));

            assertThat(team.getIsRecruiting()).isFalse();
            assertThat(team.getEndedAt()).isNotNull();
        }
    }

    // --- 픽스처 -------------------------------------------------------------
    private Team givenTeam() {
        Team team = team(TEAM_ID);
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        return team;
    }

    private Team team(long id) {
        Team team = new Team();
        team.setId(id);
        team.setTitle("팀 " + id);
        team.setLeaderUserId(LEADER_ID);
        return team;
    }

    private List<TeamCompletedEvent> capturedEvents(int expectedCount) {
        ArgumentCaptor<TeamCompletedEvent> captor = ArgumentCaptor.forClass(TeamCompletedEvent.class);
        verify(eventPublisher, times(expectedCount)).publishEvent(captor.capture());
        return captor.getAllValues();
    }
}
