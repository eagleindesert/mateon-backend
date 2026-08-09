package com.example.mateon.teams.service;

import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.notification.domain.Notification;
import com.example.mateon.notification.service.NotificationService;
import com.example.mateon.teams.domain.ApplicationStatus;
import com.example.mateon.teams.domain.OfferStatus;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamApplication;
import com.example.mateon.teams.dto.request.TeamApplicationRequestDTO;
import com.example.mateon.teams.repository.TeamApplicationRepository;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.teams.repository.TeamOfferRepository;
import com.example.mateon.teams.repository.TeamRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserCollaborationScoreRepository;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * 팀 흐름에서 <b>상대방에게 알림이 나가는지</b>를 고정한다.
 *
 * <p>여기 모인 세 이벤트(지원서 제출·지원 철회·팀 삭제)의 공통점은 <b>영향받는 사람이 스스로
 * 알아낼 방법이 없다</b>는 것이다. 지원 철회와 팀 삭제는 행을 하드 삭제하므로 목록에서 그냥
 * 사라지고, 취소 상태 같은 흔적조차 남지 않는다. 그래서 알림이 유일한 신호다.
 *
 * <p>알림은 본래 흐름의 부수효과라 리팩터링 중 조용히 빠져도 API 응답은 그대로 200 이다.
 * 실제로 이 세 지점은 오랫동안 알림 없이 동작했고 아무 테스트도 그것을 잡지 못했다. 그래서
 * 여기서 단정하는 것은 "호출이 성공했는가"가 아니라 <b>누구에게 무엇이 갔는가</b>다.
 *
 * <p>발송 실패나 SSE 전송은 보지 않는다. NotificationService.send 는 저장 + 이벤트 발행까지만
 * 하고 실제 전송은 AFTER_COMMIT 리스너의 몫이라, 이 서비스가 책임지는 경계는 send 호출까지다.
 */
class TeamServiceNotificationTest {

    private static final long TEAM_ID = 7L;
    private static final long LEADER_ID = 1L;
    private static final long APPLICANT_ID = 2L;
    private static final String TEAM_TITLE = "알림 테스트 팀";

    private TeamRepository teamRepository;
    private TeamApplicationRepository applicationRepository;
    private TeamOfferRepository offerRepository;
    private TeamMemberRepository teamMemberRepository;
    private UserRepository userRepository;
    private NotificationService notificationService;
    private TeamService service;

    private Team team;
    private User leader;
    private User applicant;

    @BeforeEach
    void setUp() {
        teamRepository = mock(TeamRepository.class);
        applicationRepository = mock(TeamApplicationRepository.class);
        offerRepository = mock(TeamOfferRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);

        service = new TeamService(teamRepository, applicationRepository, offerRepository,
                teamMemberRepository, mock(EventRepository.class), userRepository,
                mock(UserCollaborationScoreRepository.class), notificationService,
                mock(ApplicationEventPublisher.class));

        leader = givenUser(LEADER_ID, "팀장");
        applicant = givenUser(APPLICANT_ID, "지원자");
        team = givenTeam();
    }

    @Nested
    @DisplayName("지원서 제출")
    class Apply {

        @Test
        @DisplayName("팀장에게 지원자 이름이 담긴 알림이 간다")
        void notifiesLeader() {
            givenApplyable();
            givenExistingUsers(leader, applicant);

            service.applyToTeam(TEAM_ID, request(), APPLICANT_ID);

            ArgumentCaptor<User> receiver = ArgumentCaptor.forClass(User.class);
            verify(notificationService).send(receiver.capture(), eq("지원서 도착"),
                    contains("지원자 님이 지원했습니다"), eq(Notification.NotificationType.INFO));
            assertThat(receiver.getValue().getId()).isEqualTo(LEADER_ID);
        }

        @Test
        @DisplayName("팀장 계정이 사라진 팀이어도 지원 자체는 성립한다")
        void doesNotFailWhenLeaderIsGone() {
            givenApplyable();
            givenExistingUsers(applicant);  // 팀장 계정 없음

            // getUserById 를 썼다면 여기서 USER_NOT_FOUND 가 터져 정상 지원까지 막혔을 것이다.
            assertThatCode(() -> service.applyToTeam(TEAM_ID, request(), APPLICANT_ID))
                    .doesNotThrowAnyException();

            verify(applicationRepository).save(any());
            verify(notificationService, never()).send(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("중복 지원으로 차단되면 알림이 나가지 않는다")
        void silentWhenBlocked() {
            givenExistingUsers(leader, applicant);
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
            when(applicationRepository.findByTeamIdAndApplicantId(TEAM_ID, APPLICANT_ID))
                    .thenReturn(Optional.of(pendingApplication(applicant)));

            assertThatThrownBy(() -> service.applyToTeam(TEAM_ID, request(), APPLICANT_ID))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(notificationService, never()).send(any(), anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("지원 철회")
    class CancelApplication {

        @Test
        @DisplayName("팀장에게 알림이 간다 — 하드 삭제라 이것 말고는 신호가 없다")
        void notifiesLeader() {
            givenExistingUsers(leader, applicant);
            when(applicationRepository.findById(30L)).thenReturn(Optional.of(pendingApplication(applicant)));

            service.cancelApplication(30L, APPLICANT_ID);

            ArgumentCaptor<User> receiver = ArgumentCaptor.forClass(User.class);
            verify(notificationService).send(receiver.capture(), eq("지원 취소"),
                    contains("지원자 님의 지원이 취소되었습니다"), eq(Notification.NotificationType.INFO));
            assertThat(receiver.getValue().getId()).isEqualTo(LEADER_ID);
        }

        @Test
        @DisplayName("이미 처리된 지원서는 취소가 막히고 알림도 나가지 않는다")
        void silentWhenAlreadyProcessed() {
            givenExistingUsers(leader, applicant);
            TeamApplication approved = pendingApplication(applicant);
            approved.setStatus(ApplicationStatus.APPROVED);
            when(applicationRepository.findById(30L)).thenReturn(Optional.of(approved));

            assertThatThrownBy(() -> service.cancelApplication(30L, APPLICANT_ID))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(applicationRepository, never()).delete(any());
            verify(notificationService, never()).send(any(), anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("팀 삭제")
    class DeleteTeam {

        @Test
        @DisplayName("팀원·대기 지원자·대기 제안 대상 모두에게 알림이 간다")
        void notifiesEveryoneAttached() {
            User member = givenUser(3L, "팀원");
            User offered = givenUser(4L, "제안받은사람");
            givenExistingUsers(leader);
            givenAttached(List.of(member), List.of(applicant), List.of(offered));

            service.deleteTeam(TEAM_ID, LEADER_ID);

            assertThat(capturedReceiverIds(3))
                    .containsExactlyInAnyOrder(3L, APPLICANT_ID, 4L);
        }

        @Test
        @DisplayName("팀을 지운 팀장 본인에게는 알림이 가지 않는다")
        void skipsLeader() {
            givenExistingUsers(leader);
            // 팀장도 team_members 에 LEADER 로 들어 있다 (createTeam 이 행을 만든다).
            givenAttached(List.of(leader, applicant), List.of(), List.of());

            service.deleteTeam(TEAM_ID, LEADER_ID);

            assertThat(capturedReceiverIds(1)).containsExactly(APPLICANT_ID);
        }

        @Test
        @DisplayName("팀원이면서 제안 대상이기도 한 사람에게 두 번 보내지 않는다")
        void deduplicatesReceiver() {
            givenExistingUsers(leader);
            // 제안을 수락해 팀원이 된 사람은 team_members 와 team_offers 양쪽에 잡힌다.
            givenAttached(List.of(applicant), List.of(), List.of(applicant));

            service.deleteTeam(TEAM_ID, LEADER_ID);

            assertThat(capturedReceiverIds(1)).containsExactly(APPLICANT_ID);
        }

        /**
         * 거절·취소된 상대를 거르는 일은 JPQL 의 status 조건이 한다. 목으로는 그 필터링 자체를
         * 볼 수 없으므로, 대신 <b>PENDING 만 요청하는지</b>를 확인한다. 상태 조건이 빠지면
         * 이미 끝난 관계의 상대에게까지 "팀이 삭제되었습니다" 가 나간다.
         */
        @Test
        @DisplayName("대기 중인 지원서·제안만 대상으로 조회한다")
        void asksForPendingCounterpartsOnly() {
            givenExistingUsers(leader);
            givenAttached(List.of(), List.of(), List.of());

            service.deleteTeam(TEAM_ID, LEADER_ID);

            verify(applicationRepository).findApplicantsByTeamIdAndStatus(TEAM_ID, ApplicationStatus.PENDING);
            verify(offerRepository).findTargetUsersByTeamIdAndStatus(TEAM_ID, OfferStatus.PENDING);
            verify(notificationService, never()).send(any(), anyString(), anyString(), any());
        }

        /**
         * 알림은 행이 지워지기 전에 나가야 한다 — 지운 뒤엔 받을 사람을 알아낼 수 없다.
         * 그리고 수신자 조회는 소유 엔티티가 아니라 User 만 가져와야 한다. TeamMember 를 올리면
         * 팀 삭제 후 flush 에서 삭제된 Team 을 참조한 채 남아 터진다.
         */
        @Test
        @DisplayName("소유 엔티티를 올리지 않고 대상을 모은 뒤에 삭제가 일어난다")
        void deletesAfterCollectingReceivers() {
            givenExistingUsers(leader);
            givenAttached(List.of(), List.of(applicant), List.of());

            service.deleteTeam(TEAM_ID, LEADER_ID);

            verify(notificationService).send(any(), eq("팀 삭제"), anyString(), any());
            verify(teamMemberRepository, never()).findActiveMembersWithUser(TEAM_ID);
            verify(applicationRepository, never()).findByTeamId(TEAM_ID);
            verify(offerRepository, never()).findByTeamIdOrderByCreatedAtDesc(TEAM_ID);
            verify(applicationRepository).deleteByTeamId(TEAM_ID);
            verify(offerRepository).deleteByTeamId(TEAM_ID);
            verify(teamRepository).delete(team);
        }
    }

    // ── 준비 헬퍼 ────────────────────────────────────────────────────────────

    private Team givenTeam() {
        Team created = new Team();
        created.setId(TEAM_ID);
        created.setTitle(TEAM_TITLE);
        created.setLeaderUserId(LEADER_ID);
        created.setCapacity(4);
        return created;
    }

    private User givenUser(long id, String name) {
        return User.builder().id(id).name(name).schoolVerified(true).build();
    }

    /** 존재하는 계정만 등록한다. 등록하지 않은 id 는 빈 Optional 이 나와 '탈퇴한 계정'이 된다. */
    private void givenExistingUsers(User... users) {
        for (User user : users) {
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        }
    }

    private void givenApplyable() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(applicationRepository.findByTeamIdAndApplicantId(TEAM_ID, APPLICANT_ID))
                .thenReturn(Optional.empty());
    }

    /**
     * 삭제 대상 팀에 걸려 있는 사람들. 세 축 모두 <b>User 만</b> 돌려주는 쿼리라는 점이 중요하다 —
     * 소유 엔티티를 올리면 팀 삭제 후 flush 에서 터진다 (notifyTeamDeleted 주석 참고).
     *
     * <p>거절·취소된 상대는 애초에 쿼리(status = PENDING)에서 걸러지므로 여기서도 넣지 않는다.
     */
    private void givenAttached(List<User> members, List<User> pendingApplicants,
                               List<User> pendingOfferTargets) {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findActiveMemberUsers(TEAM_ID)).thenReturn(members);
        when(applicationRepository.findApplicantsByTeamIdAndStatus(TEAM_ID, ApplicationStatus.PENDING))
                .thenReturn(pendingApplicants);
        when(offerRepository.findTargetUsersByTeamIdAndStatus(TEAM_ID, OfferStatus.PENDING))
                .thenReturn(pendingOfferTargets);
    }

    private TeamApplication pendingApplication(User user) {
        return TeamApplication.builder()
                .team(team)
                .applicant(user)
                .status(ApplicationStatus.PENDING)
                .build();
    }

    private TeamApplicationRequestDTO request() {
        TeamApplicationRequestDTO dto = new TeamApplicationRequestDTO();
        dto.setIntroduction("소개");
        dto.setMessage("지원 동기");
        dto.setContactNumber("010-0000-0000");
        return dto;
    }

    private List<Long> capturedReceiverIds(int expectedCount) {
        ArgumentCaptor<User> receivers = ArgumentCaptor.forClass(User.class);
        verify(notificationService, times(expectedCount)).send(receivers.capture(), eq("팀 삭제"),
                contains(TEAM_TITLE), eq(Notification.NotificationType.INFO));
        return receivers.getAllValues().stream().map(User::getId).toList();
    }
}
