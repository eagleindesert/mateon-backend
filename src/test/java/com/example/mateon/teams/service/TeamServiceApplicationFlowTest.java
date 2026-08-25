package com.example.mateon.teams.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.notification.service.NotificationService;
import com.example.mateon.teams.domain.ApplicationStatus;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamApplication;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.domain.TeamMemberRole;
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
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 지원서 승인/거절 흐름 — 이 서비스에서 <b>두 테이블이 함께 움직이는</b> 유일한 지점이다.
 *
 * <p>
 * 승인은 {@code team_applications.status} 와 {@code team_members} 행을 같이 바꾼다.
 * 둘 중 하나만 움직이면 인원 수와 명단이 어긋나는데, 어긋난 상태로도 API 는 전부 200 이다.
 * 여기 모인 테스트는 그 어긋남이 생기는 세 가지 경로를 각각 막는다:
 *
 * <ol>
 * <li><b>이미 처리된 지원서 재처리</b> — 승인을 거절로 되돌리면 상태만 바뀌고 활성 멤버 행은
 * 남아 인원이 실제보다 커진다. 그래서 승인·거절 <i>둘 다</i> PENDING 에서만 가능하다.</li>
 * <li><b>나갔다 돌아온 멤버</b> — 행이 이미 있는데 새로 저장하면 유니크 위반이거나 중복 집계다.
 * {@code leftAt = null} 로 되살린다.</li>
 * <li><b>flush 누락</b> — 방금 {@code save()} 한 행은 아직 INSERT 전일 수 있어 카운트에
 * 안 잡힌다. flush 가 카운트보다 먼저 와야 마지막 한 명이 팀을 마감시킨다.</li>
 * </ol>
 *
 * <p>
 * 3번이 특히 조용하다. flush 를 빼도 승인은 성공하고 알림도 가며, 증상은 "정원이 다 찼는데
 * 모집 중으로 계속 떠 있다" 뿐이다 — 다음 승인 때 마감되므로 재현도 잘 안 된다.
 *
 * <p>
 * 알림 발송 자체는 {@code TeamServiceNotificationTest} 가 맡는다. 여기서는 상태 전이와
 * 순서만 본다.
 */
class TeamServiceApplicationFlowTest {

    private static final long TEAM_ID = 7L;
    private static final long LEADER_ID = 1L;
    private static final long APPLICANT_ID = 2L;
    private static final long APPLICATION_ID = 30L;

    private TeamRepository teamRepository;
    private TeamApplicationRepository applicationRepository;
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
        teamMemberRepository = mock(TeamMemberRepository.class);
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);

        service = new TeamService(teamRepository, applicationRepository,
          mock(TeamOfferRepository.class), teamMemberRepository,
          mock(EventRepository.class), userRepository,
          mock(UserCollaborationScoreRepository.class), notificationService,
          mock(ApplicationEventPublisher.class));

        leader = user(LEADER_ID, "팀장");
        applicant = user(APPLICANT_ID, "지원자");
        team = team(4);

        when(userRepository.findById(LEADER_ID)).thenReturn(Optional.of(leader));
        when(userRepository.findById(APPLICANT_ID)).thenReturn(Optional.of(applicant));
    }

    @Nested
    @DisplayName("권한과 상태")
    class Guards {

        @Test
        @DisplayName("팀장이 아니면 처리할 수 없다 (403 이 아니라 400 이다)")
        void nonLeaderIsRejected() {
            User outsider = user(9L, "남");
            when(userRepository.findById(9L)).thenReturn(Optional.of(outsider));
            givenApplication(ApplicationStatus.PENDING);

            assertThatThrownBy(() -> service.processApplication(APPLICATION_ID, true, 9L))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN_ACCESS);

            verify(teamMemberRepository, never()).save(any());
            verify(notificationService, never()).send(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("없는 지원서는 RESOURCE_NOT_FOUND 다 (이 코드는 404 가 아니라 400 이다)")
        void missingApplication() {
            when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.processApplication(APPLICATION_ID, true, LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("이미 승인된 지원서는 거절로 되돌릴 수 없다 — 멤버 행이 남아 인원이 부풀 것이다")
        void approvedCannotBeRejected() {
            TeamApplication application = givenApplication(ApplicationStatus.APPROVED);

            assertThatThrownBy(() -> service.processApplication(APPLICATION_ID, false, LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.APPLICATION_ALREADY_PROCESSED);

            assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
            verify(notificationService, never()).send(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("이미 거절된 지원서도 승인으로 뒤집을 수 없다 (양방향으로 막는다)")
        void rejectedCannotBeApproved() {
            givenApplication(ApplicationStatus.REJECTED);

            assertThatThrownBy(() -> service.processApplication(APPLICATION_ID, true, LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.APPLICATION_ALREADY_PROCESSED);

            verify(teamMemberRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("승인")
    class Approve {

        @Test
        @DisplayName("상태가 APPROVED 가 되고 MEMBER 행이 생긴다")
        void createsMemberRow() {
            TeamApplication application = givenApplication(ApplicationStatus.PENDING);
            when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, APPLICANT_ID))
              .thenReturn(Optional.empty());

            service.processApplication(APPLICATION_ID, true, LEADER_ID);

            assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPROVED);

            ArgumentCaptor<TeamMember> saved = ArgumentCaptor.forClass(TeamMember.class);
            verify(teamMemberRepository).save(saved.capture());
            assertThat(saved.getValue().getRole()).isEqualTo(TeamMemberRole.MEMBER);
            assertThat(saved.getValue().getUser().getId()).isEqualTo(APPLICANT_ID);
            assertThat(saved.getValue().getTeam()).isSameAs(team);
            assertThat(saved.getValue().isActive()).isTrue();
        }

        @Test
        @DisplayName("나갔던 멤버는 새 행을 만들지 않고 leftAt 을 지워 되살린다")
        void reactivatesInsteadOfInserting() {
            givenApplication(ApplicationStatus.PENDING);
            TeamMember left = TeamMember.of(team, applicant, TeamMemberRole.MEMBER);
            left.setLeftAt(LocalDateTime.now().minusDays(3));
            when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, APPLICANT_ID))
              .thenReturn(Optional.of(left));

            service.processApplication(APPLICATION_ID, true, LEADER_ID);

            assertThat(left.getLeftAt()).isNull();
            assertThat(left.isActive()).isTrue();
            // 중복 삽입이면 유니크 위반이거나 인원이 두 번 세어진다.
            verify(teamMemberRepository, never()).save(any());
        }

        /**
         * {@code save()} 는 아직 INSERT 를 내지 않았을 수 있다. flush 가 카운트보다 늦으면
         * 방금 승인한 멤버가 집계에서 빠져 마지막 한 자리를 채워도 팀이 마감되지 않는다.
         */
        @Test
        @DisplayName("flush 가 인원 집계보다 먼저다 — 순서가 바뀌면 팀이 영영 마감되지 않는다")
        void flushBeforeCount() {
            givenApplication(ApplicationStatus.PENDING);
            when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, APPLICANT_ID))
              .thenReturn(Optional.empty());
            when(teamMemberRepository.countByTeamIdAndLeftAtIsNull(TEAM_ID)).thenReturn(4);

            service.processApplication(APPLICATION_ID, true, LEADER_ID);

            InOrder order = inOrder(teamMemberRepository);
            order.verify(teamMemberRepository).save(any());
            order.verify(teamMemberRepository).flush();
            order.verify(teamMemberRepository).countByTeamIdAndLeftAtIsNull(TEAM_ID);
        }

        @Test
        @DisplayName("정원이 차면 모집이 마감된다 (capacity 는 팀장을 포함한 값이다)")
        void closesRecruitingWhenFull() {
            givenApplication(ApplicationStatus.PENDING);
            when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, APPLICANT_ID))
              .thenReturn(Optional.empty());
            when(teamMemberRepository.countByTeamIdAndLeftAtIsNull(TEAM_ID)).thenReturn(4);

            service.processApplication(APPLICATION_ID, true, LEADER_ID);

            assertThat(team.getIsRecruiting()).isFalse();
        }

        @Test
        @DisplayName("아직 자리가 남았으면 모집 중을 유지한다")
        void staysRecruitingWhenNotFull() {
            givenApplication(ApplicationStatus.PENDING);
            when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, APPLICANT_ID))
              .thenReturn(Optional.empty());
            when(teamMemberRepository.countByTeamIdAndLeftAtIsNull(TEAM_ID)).thenReturn(3);

            service.processApplication(APPLICATION_ID, true, LEADER_ID);

            assertThat(team.getIsRecruiting()).isTrue();
        }

        @Test
        @DisplayName("정원이 없는 팀은 마감 판단 자체를 하지 않는다 (무제한 모집)")
        void noCapacityNeverCloses() {
            team.setCapacity(null);
            givenApplication(ApplicationStatus.PENDING);
            when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, APPLICANT_ID))
              .thenReturn(Optional.empty());
            when(teamMemberRepository.countByTeamIdAndLeftAtIsNull(TEAM_ID)).thenReturn(999);

            service.processApplication(APPLICATION_ID, true, LEADER_ID);

            assertThat(team.getIsRecruiting()).isTrue();
        }
    }

    @Nested
    @DisplayName("거절")
    class Reject {

        @Test
        @DisplayName("상태만 바뀌고 멤버 행도 인원 집계도 건드리지 않는다")
        void touchesNothingElse() {
            TeamApplication application = givenApplication(ApplicationStatus.PENDING);

            service.processApplication(APPLICATION_ID, false, LEADER_ID);

            assertThat(application.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
            assertThat(team.getIsRecruiting()).isTrue();
            verify(teamMemberRepository, never()).save(any());
            verify(teamMemberRepository, never()).flush();
            verify(teamMemberRepository, never()).countByTeamIdAndLeftAtIsNull(anyLong());
        }
    }

    @Nested
    @DisplayName("지원서 수정·취소 — 비-PENDING 은 MateonException 이 아니라 IllegalArgumentException 이다")
    class EditAndCancel {

        @Test
        @DisplayName("본인이 아니면 수정할 수 없다")
        void updateRequiresOwner() {
            givenApplication(ApplicationStatus.PENDING);

            assertThatThrownBy(() -> service.updateApplication(APPLICATION_ID, request(), LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN_ACCESS);
        }

        @Test
        @DisplayName("PENDING 이면 내용이 갱신된다 (save 없이 더티 체킹)")
        void updatesFields() {
            TeamApplication application = givenApplication(ApplicationStatus.PENDING);

            service.updateApplication(APPLICATION_ID, request(), APPLICANT_ID);

            assertThat(application.getIntroduction()).isEqualTo("바뀐 소개");
            assertThat(application.getMessage()).isEqualTo("바뀐 동기");
            assertThat(application.getContactNumber()).isEqualTo("010-1111-2222");
            assertThat(application.getPortfolioUrl()).isEqualTo("https://portfolio.example");
            verify(applicationRepository, never()).save(any());
        }

        /**
         * 같은 "이미 처리됨" 상황인데 {@code processApplication} 은 {@code MateonException}
         * ({@code APPLICATION_ALREADY_PROCESSED}) 를, 이쪽 둘은 평범한
         * {@code IllegalArgumentException} 을 던진다. 둘 다 400 으로 나가지만 응답의
         * {@code message} 가 에러 코드 문구가 아니라 여기 적힌 한글 문장이다 — 프론트가
         * 코드로 분기할 수 없다. 현재 동작을 못박아 둔다.
         */
        @Test
        @DisplayName("이미 처리된 지원서는 수정도 취소도 IllegalArgumentException 이다 (에러 코드가 없다)")
        void processedIsPlainIllegalArgument() {
            givenApplication(ApplicationStatus.APPROVED);

            assertThatThrownBy(() -> service.updateApplication(APPLICATION_ID, request(), APPLICANT_ID))
              .isInstanceOf(IllegalArgumentException.class)
              .isNotInstanceOf(MateonException.class)
              .hasMessage("이미 처리된 지원서는 수정할 수 없습니다.");

            assertThatThrownBy(() -> service.cancelApplication(APPLICATION_ID, APPLICANT_ID))
              .isInstanceOf(IllegalArgumentException.class)
              .isNotInstanceOf(MateonException.class)
              .hasMessage("이미 처리된 지원서는 취소할 수 없습니다.");

            verify(applicationRepository, never()).delete(any());
        }

        @Test
        @DisplayName("남의 지원서는 취소할 수 없다")
        void cancelRequiresOwner() {
            givenApplication(ApplicationStatus.PENDING);

            assertThatThrownBy(() -> service.cancelApplication(APPLICATION_ID, LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN_ACCESS);

            verify(applicationRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("지원 자격")
    class ApplyGuards {

        @Test
        @DisplayName("학교 인증 전이면 어떤 조회보다 먼저 막힌다")
        void schoolVerificationComesFirst() {
            User unverified = User.builder().id(APPLICANT_ID).name("미인증").schoolVerified(false).build();
            when(userRepository.findById(APPLICANT_ID)).thenReturn(Optional.of(unverified));

            assertThatThrownBy(() -> service.applyToTeam(TEAM_ID, request(), APPLICANT_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.SCHOOL_NOT_VERIFIED);

            verify(teamRepository, never()).findById(anyLong());
            verify(applicationRepository, never()).save(any());
        }

        @Test
        @DisplayName("본인 팀에는 지원할 수 없다")
        void cannotApplyToOwnTeam() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));

            assertThatThrownBy(() -> service.applyToTeam(TEAM_ID, request(), LEADER_ID))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessage("본인이 개설한 팀에는 지원할 수 없습니다.");

            verify(applicationRepository, never()).save(any());
        }

        @Test
        @DisplayName("지원서에 DTO 의 모든 필드가 담겨 PENDING 으로 저장된다")
        void savesAllFields() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
            when(applicationRepository.findByTeamIdAndApplicantId(TEAM_ID, APPLICANT_ID))
              .thenReturn(Optional.empty());

            service.applyToTeam(TEAM_ID, request(), APPLICANT_ID);

            ArgumentCaptor<TeamApplication> saved = ArgumentCaptor.forClass(TeamApplication.class);
            verify(applicationRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(ApplicationStatus.PENDING);
            assertThat(saved.getValue().getIntroduction()).isEqualTo("바뀐 소개");
            assertThat(saved.getValue().getMessage()).isEqualTo("바뀐 동기");
            assertThat(saved.getValue().getContactNumber()).isEqualTo("010-1111-2222");
            assertThat(saved.getValue().getPortfolioUrl()).isEqualTo("https://portfolio.example");
            assertThat(saved.getValue().getApplicant().getId()).isEqualTo(APPLICANT_ID);
        }
    }

    @Nested
    @DisplayName("지원서 상세 조회 — 당사자와 팀장만")
    class Detail {

        @Test
        @DisplayName("지원자 본인은 볼 수 있다")
        void applicantCanRead() {
            givenApplication(ApplicationStatus.PENDING);

            assertThat(service.getApplicationDetail(APPLICATION_ID, APPLICANT_ID)).isNotNull();
        }

        @Test
        @DisplayName("팀장도 볼 수 있다")
        void leaderCanRead() {
            givenApplication(ApplicationStatus.PENDING);

            assertThat(service.getApplicationDetail(APPLICATION_ID, LEADER_ID)).isNotNull();
        }

        @Test
        @DisplayName("제3자는 볼 수 없다 (다른 지원자의 연락처가 들어 있다)")
        void outsiderCannotRead() {
            when(userRepository.findById(9L)).thenReturn(Optional.of(user(9L, "남")));
            givenApplication(ApplicationStatus.PENDING);

            assertThatThrownBy(() -> service.getApplicationDetail(APPLICATION_ID, 9L))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN_ACCESS);
        }
    }

    @Test
    @DisplayName("내 팀의 지원서 목록은 팀장만 볼 수 있다")
    void applicationsForMyTeamRequiresLeader() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> service.getApplicationsForMyTeam(TEAM_ID, APPLICANT_ID))
          .isInstanceOf(MateonException.class)
          .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN_ACCESS);

        verify(applicationRepository, never()).findByTeamId(anyLong());
    }

    @Test
    @DisplayName("내 지원 목록은 지원자 id 로 조회한다")
    void myApplications() {
        when(applicationRepository.findByApplicantId(APPLICANT_ID)).thenReturn(List.of());

        assertThat(service.getMyApplications(APPLICANT_ID)).isEmpty();

        verify(applicationRepository).findByApplicantId(APPLICANT_ID);
    }

    // --- 픽스처 -------------------------------------------------------------
    private TeamApplication givenApplication(ApplicationStatus status) {
        TeamApplication application = TeamApplication.builder()
          .team(team)
          .applicant(applicant)
          .status(status)
          .build();
        when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
        return application;
    }

    private Team team(Integer capacity) {
        Team created = new Team();
        created.setId(TEAM_ID);
        created.setTitle("테스트 팀");
        created.setLeaderUserId(LEADER_ID);
        created.setCapacity(capacity);
        return created;
    }

    private User user(long id, String name) {
        return User.builder().id(id).name(name).schoolVerified(true).build();
    }

    private TeamApplicationRequestDTO request() {
        TeamApplicationRequestDTO dto = new TeamApplicationRequestDTO();
        dto.setIntroduction("바뀐 소개");
        dto.setMessage("바뀐 동기");
        dto.setContactNumber("010-1111-2222");
        dto.setPortfolioUrl("https://portfolio.example");
        return dto;
    }
}
