package com.example.mateon.teams.event;

import com.example.mateon.notification.domain.Notification;
import com.example.mateon.notification.service.NotificationService;
import com.example.mateon.teams.config.CollaborationProperties;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.domain.TeamMemberRole;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.teams.repository.TeamRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 팀 종료 알림이 <b>혼자인 팀</b>에서 어떻게 갈리는지 고정한다.
 *
 * <p>혼자인 팀에 평가 요청을 보내지 않는 것은 옳다 — 평가할 상대가 없으니 소음이다. 그런데 그
 * early return 때문에 <b>스케줄러가 마감일 경과로 닫은 1인 팀의 팀장은 아무것도 받지 못했다</b>.
 * 본인이 한 일이 아니므로 모집이 닫히고 활동이 끝난 사실 자체를 알 길이 없었다.
 *
 * <p>그래서 종료 경로를 구분한다. 팀장이 직접 누른 종료는 본인이 아는 일이라 여전히 침묵하고,
 * 스케줄러가 닫은 경우에만 알린다. 아래 두 테스트가 그 갈림을 잠근다 — 한쪽만 검증하면
 * "항상 보낸다" 로 퇴화해도 통과한다.
 */
class TeamCompletedNotificationListenerTest {

    private static final long TEAM_ID = 7L;
    private static final long LEADER_ID = 1L;
    private static final String TEAM_TITLE = "종료 테스트 팀";

    private TeamMemberRepository teamMemberRepository;
    private UserRepository userRepository;
    private NotificationService notificationService;
    private TeamCompletedNotificationListener listener;

    private Team team;
    private User leader;

    @BeforeEach
    void setUp() {
        TeamRepository teamRepository = mock(TeamRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);

        CollaborationProperties properties = mock(CollaborationProperties.class);
        when(properties.getReviewWindowDays()).thenReturn(14);

        listener = new TeamCompletedNotificationListener(teamRepository, teamMemberRepository,
                userRepository, notificationService, properties);

        team = new Team();
        team.setId(TEAM_ID);
        team.setTitle(TEAM_TITLE);
        team.setLeaderUserId(LEADER_ID);
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));

        leader = givenUser(LEADER_ID, "팀장");
        when(userRepository.findById(LEADER_ID)).thenReturn(Optional.of(leader));
    }

    @Test
    @DisplayName("스케줄러가 닫은 1인 팀은 팀장에게 자동 종료를 알린다")
    void notifiesLeaderWhenSoloTeamAutoCompleted() {
        givenMembers(leader);

        listener.onTeamCompleted(new TeamCompletedEvent(TEAM_ID, true));

        ArgumentCaptor<User> receiver = ArgumentCaptor.forClass(User.class);
        verify(notificationService).send(receiver.capture(), eq("활동 자동 종료"),
                contains(TEAM_TITLE), eq(Notification.NotificationType.INFO));
        assertThat(receiver.getValue().getId()).isEqualTo(LEADER_ID);

        // 혼자라 평가할 상대가 없다.
        verify(notificationService, never()).send(any(), eq("팀원 평가 요청"), anyString(), any());
    }

    @Test
    @DisplayName("팀장이 직접 종료한 1인 팀에는 아무 알림도 가지 않는다")
    void silentWhenSoloTeamCompletedByLeader() {
        givenMembers(leader);

        listener.onTeamCompleted(new TeamCompletedEvent(TEAM_ID, false));

        verify(notificationService, never()).send(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("LEADER 행이 없는 옛 팀도 자동 종료면 팀장을 찾아 알린다")
    void notifiesLeaderWhenMemberRowMissing() {
        givenMembers();  // V12 백필 이전 팀 — team_members 가 비어 있다

        listener.onTeamCompleted(new TeamCompletedEvent(TEAM_ID, true));

        verify(notificationService).send(eq(leader), eq("활동 자동 종료"), anyString(),
                eq(Notification.NotificationType.INFO));
    }

    @Test
    @DisplayName("두 명 이상이면 자동 종료여도 평가 요청만 나간다")
    void sendsReviewRequestWhenTeamHasPeers() {
        givenMembers(leader, givenUser(2L, "팀원"));

        listener.onTeamCompleted(new TeamCompletedEvent(TEAM_ID, true));

        verify(notificationService, times(2)).send(any(), eq("팀원 평가 요청"),
                contains("14일 안에"), eq(Notification.NotificationType.INFO));
        verify(notificationService, never()).send(any(), eq("활동 자동 종료"), anyString(), any());
    }

    // ── 준비 헬퍼 ────────────────────────────────────────────────────────────

    private User givenUser(long id, String name) {
        return User.builder().id(id).name(name).schoolVerified(true).build();
    }

    private void givenMembers(User... users) {
        List<TeamMember> members = List.of(users).stream()
                .map(user -> TeamMember.of(team, user, TeamMemberRole.MEMBER))
                .toList();
        when(teamMemberRepository.findActiveMembersWithUser(TEAM_ID)).thenReturn(members);
    }
}
