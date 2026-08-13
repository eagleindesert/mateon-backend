package com.example.mateon.teams.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.events.models.Event;
import com.example.mateon.events.repository.EventRepository;
import com.example.mateon.notification.service.NotificationService;
import com.example.mateon.teams.domain.ApplicationStatus;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamApplication;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.domain.TeamMemberRole;
import com.example.mateon.teams.dto.request.TeamRequestDTO;
import com.example.mateon.teams.dto.response.TeamDetailResponseDTO;
import com.example.mateon.teams.dto.response.TeamResponseDTO;
import com.example.mateon.teams.event.TeamEmbeddingRefreshRequestedEvent;
import com.example.mateon.teams.repository.TeamApplicationRepository;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.teams.repository.TeamOfferRepository;
import com.example.mateon.teams.repository.TeamRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.domain.UserCollaborationScore;
import com.example.mateon.user.repository.UserCollaborationScoreRepository;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 팀 모집글의 생성·조회·수정·삭제.
 *
 * <p>목록 조회에서 고정할 것은 필터의 <b>배타적 우선순위</b>다: {@code myPosts} → {@code eventId}
 * → {@code category}. 세 개를 동시에 보내면 앞의 것만 적용되고 뒤의 것은 조용히 무시된다.
 * 프론트가 "활동 안에서 카테고리로 다시 거르기"를 시도하면 활동 필터만 먹은 결과를 받는데,
 * 화면에는 팀이 뜨므로 잘못됐다는 신호가 없다. 게다가 {@code myPosts} 와 {@code category="전체"}
 * 를 뺀 나머지 경로는 <b>모집 중 여부를 보지 않는다</b> — 마감된 팀도 목록에 섞인다.
 *
 * <p>상세 조회에서 고정할 것은 <b>추가 쿼리를 내지 않는 것</b>이다. 팀장 정보는 이미 읽은 명단의
 * LEADER 행에서 꺼낸다. 여기서 {@code userRepository} 를 한 번 더 부르면 팀 목록 화면에서
 * N+1 이 된다 — 성능 문제는 테스트가 잡아 주지 않으면 리팩터링 중에 슬그머니 돌아온다.
 * 반대로 V12 백필 이전에 만들어져 LEADER 행이 없는 팀에서는 폴백이 <b>반드시</b> 동작해야 한다.
 *
 * <p>생성에서는 세 가지가 함께 일어난다: 팀 저장, LEADER 멤버 행 생성, 임베딩 재계산 이벤트 발행.
 * 두 번째가 빠지면 인원 집계가 팀장을 놓치고, 세 번째가 빠지면 그 팀은 영영 추천에 뜨지 않는다
 * (둘 다 화면에는 아무 표시가 없다).
 */
class TeamServiceCrudTest {

    private static final long TEAM_ID = 7L;
    private static final long LEADER_ID = 1L;
    private static final long EVENT_ID = 3L;

    private TeamRepository teamRepository;
    private TeamApplicationRepository applicationRepository;
    private TeamOfferRepository offerRepository;
    private TeamMemberRepository teamMemberRepository;
    private EventRepository eventRepository;
    private UserRepository userRepository;
    private UserCollaborationScoreRepository collaborationScoreRepository;
    private ApplicationEventPublisher eventPublisher;
    private TeamService service;

    private User leader;

    @BeforeEach
    void setUp() {
        teamRepository = mock(TeamRepository.class);
        applicationRepository = mock(TeamApplicationRepository.class);
        offerRepository = mock(TeamOfferRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        eventRepository = mock(EventRepository.class);
        userRepository = mock(UserRepository.class);
        collaborationScoreRepository = mock(UserCollaborationScoreRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        service = new TeamService(teamRepository, applicationRepository, offerRepository,
                teamMemberRepository, eventRepository, userRepository,
                collaborationScoreRepository, mock(NotificationService.class), eventPublisher);

        leader = user(LEADER_ID, "팀장");
        when(userRepository.findById(LEADER_ID)).thenReturn(Optional.of(leader));
    }

    @Nested
    @DisplayName("목록 조회 — 필터는 배타적이고 순서가 있다")
    class GetTeams {

        @Test
        @DisplayName("myPosts 가 켜지면 eventId 와 category 는 무시된다")
        void myPostsWins() {
            when(teamRepository.findByLeaderUserId(LEADER_ID)).thenReturn(List.of(team()));

            service.getTeams(EVENT_ID, "개발", true, LEADER_ID);

            verify(teamRepository).findByLeaderUserId(LEADER_ID);
            verify(teamRepository, never()).findByEventIdAndIsRecruitingTrue(anyLong());
            verify(teamRepository, never()).findByEventCategory(anyString());
        }

        @Test
        @DisplayName("eventId 는 category 보다 앞선다 — 활동 안에서 카테고리로 다시 거를 수 없다")
        void eventIdBeatsCategory() {
            when(teamRepository.findByEventIdAndIsRecruitingTrue(EVENT_ID)).thenReturn(List.of());

            service.getTeams(EVENT_ID, "개발", false, LEADER_ID);

            verify(teamRepository).findByEventIdAndIsRecruitingTrue(EVENT_ID);
            verify(teamRepository, never()).findByEventCategory(anyString());
        }

        @Test
        @DisplayName("\"자율\"은 활동에 연결되지 않은 팀을 뜻한다 (카테고리 이름이 아니다)")
        void jayulMeansNoEvent() {
            when(teamRepository.findAllByEventIdIsNull()).thenReturn(List.of());

            service.getTeams(null, "자율", false, LEADER_ID);

            verify(teamRepository).findAllByEventIdIsNull();
            verify(teamRepository, never()).findByEventCategory(anyString());
        }

        @Test
        @DisplayName("\"전체\"는 필터가 아니라 전체 조회다")
        void jeoncheMeansFindAll() {
            when(teamRepository.findAll()).thenReturn(List.of());

            service.getTeams(null, "전체", false, LEADER_ID);

            verify(teamRepository).findAll();
            verify(teamRepository, never()).findByEventCategory(anyString());
        }

        @Test
        @DisplayName("그 밖의 카테고리는 활동 카테고리로 조회한다")
        void otherCategory() {
            when(teamRepository.findByEventCategory("개발")).thenReturn(List.of());

            service.getTeams(null, "개발", false, LEADER_ID);

            verify(teamRepository).findByEventCategory("개발");
        }

        @Test
        @DisplayName("아무 조건도 없으면 전체 조회다")
        void noFilter() {
            when(teamRepository.findAll()).thenReturn(List.of());

            service.getTeams(null, null, false, LEADER_ID);

            verify(teamRepository).findAll();
        }

        @Test
        @DisplayName("myPosts 일 때만 유저를 조회한다 (익명 열람 경로에서 USER_NOT_FOUND 가 나면 안 된다)")
        void looksUpUserOnlyForMyPosts() {
            when(teamRepository.findAll()).thenReturn(List.of());

            service.getTeams(null, null, false, null);

            verify(userRepository, never()).findById(any());
        }

        @Test
        @DisplayName("각 팀의 인원 수와 연결 활동을 채워 준다")
        void fillsCountAndEvent() {
            Team team = team();
            team.setEventId(EVENT_ID);
            when(teamRepository.findAll()).thenReturn(List.of(team));
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event()));
            when(teamMemberRepository.countByTeamIdAndLeftAtIsNull(TEAM_ID)).thenReturn(2);

            List<TeamResponseDTO> teams = service.getTeams(null, null, false, LEADER_ID);

            assertThat(teams).hasSize(1);
            assertThat(teams.get(0).getCurrentMemberCount()).isEqualTo(2);
            assertThat(teams.get(0).getConnectedActivityTitle()).isEqualTo("교내 해커톤");
        }

        @Test
        @DisplayName("활동이 삭제된 팀도 목록에서 빠지지 않는다 (활동 정보만 비어 있다)")
        void missingEventDoesNotDropTeam() {
            Team team = team();
            team.setEventId(EVENT_ID);
            when(teamRepository.findAll()).thenReturn(List.of(team));
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

            List<TeamResponseDTO> teams = service.getTeams(null, null, false, LEADER_ID);

            assertThat(teams).hasSize(1);
            assertThat(teams.get(0).getConnectedActivityTitle()).isNull();
        }

        @Test
        @DisplayName("자율 팀은 활동 조회 자체를 하지 않는다")
        void standaloneTeamSkipsEventLookup() {
            Team team = team();
            team.setEventId(null);
            when(teamRepository.findAll()).thenReturn(List.of(team));

            service.getTeams(null, null, false, LEADER_ID);

            verify(eventRepository, never()).findById(any());
        }
    }

    @Nested
    @DisplayName("상세 조회")
    class Detail {

        @Test
        @DisplayName("팀장 정보는 이미 읽은 명단에서 꺼낸다 — 추가 조회 0회")
        void leaderComesFromMemberList() {
            givenTeamWithMembers(List.of(member(leader, TeamMemberRole.LEADER)));

            TeamDetailResponseDTO detail = service.getTeamDetail(TEAM_ID, null);

            assertThat(detail.getLeaderName()).isEqualTo("팀장");
            // userId 가 null 이라 로그인 유저 조회도 없고, 팀장 폴백 조회도 없어야 한다.
            verify(userRepository, never()).findById(any());
        }

        @Test
        @DisplayName("LEADER 행이 없는 옛날 팀만 조회로 폴백한다 (V12 백필 공백)")
        void fallsBackWhenLeaderRowMissing() {
            givenTeamWithMembers(List.of(member(user(2L, "팀원"), TeamMemberRole.MEMBER)));

            TeamDetailResponseDTO detail = service.getTeamDetail(TEAM_ID, null);

            assertThat(detail.getLeaderName()).isEqualTo("팀장");
            verify(userRepository, times(1)).findById(LEADER_ID);
        }

        @Test
        @DisplayName("폴백 대상 계정마저 없으면 USER_NOT_FOUND 다")
        void fallbackMissingUser() {
            givenTeamWithMembers(List.of());
            when(userRepository.findById(LEADER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTeamDetail(TEAM_ID, null))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("인원 수는 명단의 크기다 (따로 세지 않는다 — 숫자와 명단이 어긋날 수 없다)")
        void countIsMemberListSize() {
            givenTeamWithMembers(List.of(
                    member(leader, TeamMemberRole.LEADER),
                    member(user(2L, "팀원"), TeamMemberRole.MEMBER)));

            TeamDetailResponseDTO detail = service.getTeamDetail(TEAM_ID, null);

            assertThat(detail.getCurrentMemberCount()).isEqualTo(2);
            assertThat(detail.getMembers()).hasSize(2);
            verify(teamMemberRepository, never()).countByTeamIdAndLeftAtIsNull(anyLong());
        }

        @Test
        @DisplayName("비로그인 조회는 지원 여부를 확인하지 않는다")
        void anonymousSkipsApplicationLookup() {
            givenTeamWithMembers(List.of(member(leader, TeamMemberRole.LEADER)));

            TeamDetailResponseDTO detail = service.getTeamDetail(TEAM_ID, null);

            assertThat(detail.isLeader()).isFalse();
            assertThat(detail.isHasApplied()).isFalse();
            verify(applicationRepository, never()).findByTeamIdAndApplicantId(anyLong(), anyLong());
        }

        @Test
        @DisplayName("로그인 유저의 지원 상태가 담기고 hasApplied 가 거기서 파생된다")
        void carriesMyApplicationStatus() {
            User applicant = user(2L, "지원자");
            when(userRepository.findById(2L)).thenReturn(Optional.of(applicant));
            givenTeamWithMembers(List.of(member(leader, TeamMemberRole.LEADER)));
            when(applicationRepository.findByTeamIdAndApplicantId(TEAM_ID, 2L))
                    .thenReturn(Optional.of(TeamApplication.builder()
                            .status(ApplicationStatus.PENDING).build()));

            TeamDetailResponseDTO detail = service.getTeamDetail(TEAM_ID, 2L);

            assertThat(detail.getMyApplicationStatus()).isEqualTo(ApplicationStatus.PENDING);
            assertThat(detail.isHasApplied()).isTrue();
            assertThat(detail.isLeader()).isFalse();
        }

        @Test
        @DisplayName("탈퇴한 계정으로 조회해도 상세는 열린다 (열람 자체는 막지 않는다)")
        void deletedAccountStillReads() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());
            givenTeamWithMembers(List.of(member(leader, TeamMemberRole.LEADER)));

            TeamDetailResponseDTO detail = service.getTeamDetail(TEAM_ID, 99L);

            assertThat(detail.isLeader()).isFalse();
            assertThat(detail.isHasApplied()).isFalse();
        }

        @Test
        @DisplayName("평가 이력이 없는 팀장의 협업 온도는 36.5 다 (null 이 아니다)")
        void temperatureFallsBackToInitial() {
            givenTeamWithMembers(List.of(member(leader, TeamMemberRole.LEADER)));
            when(collaborationScoreRepository.findById(LEADER_ID)).thenReturn(Optional.empty());

            assertThat(service.getTeamDetail(TEAM_ID, null).getLeaderCollaborationTemperature())
                    .isEqualByComparingTo(CollaborationTemperatureCalculator.INITIAL);
        }

        @Test
        @DisplayName("집계 행이 있으면 그 온도가 나간다")
        void temperatureFromScoreRow() {
            givenTeamWithMembers(List.of(member(leader, TeamMemberRole.LEADER)));
            UserCollaborationScore score = UserCollaborationScore.init(LEADER_ID);
            ReflectionTestUtils.setField(score, "temperature", new BigDecimal("42.30"));
            when(collaborationScoreRepository.findById(LEADER_ID)).thenReturn(Optional.of(score));

            assertThat(service.getTeamDetail(TEAM_ID, null).getLeaderCollaborationTemperature())
                    .isEqualByComparingTo(new BigDecimal("42.30"));
        }

        @Test
        @DisplayName("없는 팀은 RESOURCE_NOT_FOUND 다")
        void missingTeam() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTeamDetail(TEAM_ID, null))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        @DisplayName("학교 인증 전이면 어떤 저장보다 먼저 막힌다")
        void schoolVerificationComesFirst() {
            when(userRepository.findById(LEADER_ID))
                    .thenReturn(Optional.of(User.builder().id(LEADER_ID).schoolVerified(false).build()));

            assertThatThrownBy(() -> service.createTeam(request(null), LEADER_ID))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.SCHOOL_NOT_VERIFIED);

            verify(teamRepository, never()).save(any());
            verify(teamMemberRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("팀장도 LEADER 멤버 행으로 저장된다 (인원 집계가 team_members 만 보면 되게)")
        void savesLeaderAsMember() {
            service.createTeam(request(null), LEADER_ID);

            ArgumentCaptor<TeamMember> member = ArgumentCaptor.forClass(TeamMember.class);
            verify(teamMemberRepository).save(member.capture());
            assertThat(member.getValue().getRole()).isEqualTo(TeamMemberRole.LEADER);
            assertThat(member.getValue().getUser().getId()).isEqualTo(LEADER_ID);
        }

        @Test
        @DisplayName("임베딩 재계산 이벤트를 발행한다 — 빠지면 그 팀은 영영 추천에 뜨지 않는다")
        void publishesEmbeddingEvent() {
            service.createTeam(request(null), LEADER_ID);

            verify(eventPublisher).publishEvent(any(TeamEmbeddingRefreshRequestedEvent.class));
        }

        @Test
        @DisplayName("갓 만든 팀의 인원은 팀장 1명이다 (집계 쿼리를 내지 않는다)")
        void memberCountIsOne() {
            TeamResponseDTO created = service.createTeam(request(null), LEADER_ID);

            assertThat(created.getCurrentMemberCount()).isEqualTo(1);
            assertThat(created.getLeaderId()).isEqualTo(LEADER_ID);
            assertThat(created.isRecruiting()).isTrue();
            verify(teamMemberRepository, never()).countByTeamIdAndLeftAtIsNull(anyLong());
        }

        /**
         * 순서상의 흠결을 그대로 문서화한다. 활동 존재 확인이 {@code teamRepository.save()} 와
         * 멤버 행 저장 <i>뒤에</i> 있어서, 없는 {@code eventId} 로 만들면 예외가 나기 전에 이미
         * 저장이 일어난다. 지금은 같은 트랜잭션이라 롤백돼 실제 피해가 없지만, 누가
         * {@code @Transactional} 을 떼거나 이 메서드를 쪼개면 고아 팀이 남는다.
         */
        @Test
        @DisplayName("모르는 eventId 는 팀을 이미 저장한 뒤에 터진다 (트랜잭션 롤백에 기대고 있다)")
        void unknownEventFailsAfterSave() {
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createTeam(request(EVENT_ID), LEADER_ID))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

            verify(teamRepository).save(any());
            verify(teamMemberRepository).save(any());
        }

        @Test
        @DisplayName("연결 활동이 있으면 응답에 활동 정보가 담긴다")
        void carriesEvent() {
            when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event()));

            TeamResponseDTO created = service.createTeam(request(EVENT_ID), LEADER_ID);

            assertThat(created.getEventId()).isEqualTo(EVENT_ID);
            assertThat(created.getConnectedActivityTitle()).isEqualTo("교내 해커톤");
        }
    }

    @Nested
    @DisplayName("수정")
    class Update {

        @Test
        @DisplayName("팀장이 아니면 수정할 수 없다")
        void nonLeaderRejected() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team()));
            when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "남")));

            assertThatThrownBy(() -> service.updateTeam(TEAM_ID, request(null), 2L))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN_ACCESS);

            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("필드가 갱신되고 임베딩 재계산이 다시 발행된다 (diff 없이 항상 — 멱등이다)")
        void updatesAndRepublishes() {
            Team team = team();
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));

            service.updateTeam(TEAM_ID, request(null), LEADER_ID);

            assertThat(team.getTitle()).isEqualTo("새 팀");
            assertThat(team.getCapacity()).isEqualTo(4);
            assertThat(team.getRole()).containsExactly("백엔드");
            verify(eventPublisher).publishEvent(any(TeamEmbeddingRefreshRequestedEvent.class));
        }

        /**
         * {@code TeamRequestDTO} 에는 {@code eventId} 가 있지만 수정에서는 읽지 않는다.
         * 연결 활동은 만든 뒤에 바꿀 수 없다 — 프론트가 수정 폼에 활동 선택을 그대로 두면
         * 사용자는 바꿨다고 믿는데 서버는 무시한다.
         */
        @Test
        @DisplayName("연결 활동은 수정으로 바뀌지 않는다 (요청의 eventId 는 무시된다)")
        void eventIdIsNotUpdatable() {
            Team team = team();
            team.setEventId(null);
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));

            service.updateTeam(TEAM_ID, request(EVENT_ID), LEADER_ID);

            assertThat(team.getEventId()).isNull();
        }
    }

    @Nested
    @DisplayName("삭제")
    class Delete {

        @Test
        @DisplayName("팀장이 아니면 삭제할 수 없다")
        void nonLeaderRejected() {
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team()));
            when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "남")));

            assertThatThrownBy(() -> service.deleteTeam(TEAM_ID, 2L))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN_ACCESS);

            verify(teamRepository, never()).delete(any());
        }

        @Test
        @DisplayName("지원서와 제안을 먼저 지우고 팀을 지운다")
        void deletesChildrenFirst() {
            Team team = team();
            when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
            when(teamMemberRepository.findActiveMemberUsers(TEAM_ID)).thenReturn(List.of());
            when(applicationRepository.findApplicantsByTeamIdAndStatus(anyLong(), any()))
                    .thenReturn(List.of());
            when(offerRepository.findTargetUsersByTeamIdAndStatus(anyLong(), any()))
                    .thenReturn(List.of());

            service.deleteTeam(TEAM_ID, LEADER_ID);

            verify(applicationRepository).deleteByTeamId(TEAM_ID);
            verify(offerRepository).deleteByTeamId(TEAM_ID);
            verify(teamRepository).delete(team);
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private void givenTeamWithMembers(List<TeamMember> members) {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team()));
        when(teamMemberRepository.findActiveMembersWithUser(TEAM_ID)).thenReturn(members);
    }

    private Team team() {
        Team team = new Team();
        team.setId(TEAM_ID);
        team.setTitle("테스트 팀");
        team.setLeaderUserId(LEADER_ID);
        team.setCapacity(4);
        team.setRole(List.of("기획"));
        return team;
    }

    private TeamMember member(User user, TeamMemberRole role) {
        return TeamMember.of(team(), user, role);
    }

    private Event event() {
        Event event = new Event();
        event.setId(EVENT_ID);
        event.setTitle("교내 해커톤");
        event.setSummarizedDescription("이틀간 진행");
        return event;
    }

    private User user(long id, String name) {
        return User.builder().id(id).name(name).major("컴퓨터공학").schoolVerified(true).build();
    }

    private TeamRequestDTO request(Long eventId) {
        TeamRequestDTO dto = new TeamRequestDTO();
        dto.setEventId(eventId);
        dto.setTitle("새 팀");
        dto.setRole(List.of("백엔드"));
        dto.setRequiredSkills(List.of("Spring"));
        dto.setCapacity(4);
        dto.setPromotionText("같이 해요");
        dto.setCharacteristic("온라인");
        dto.setRecruitmentStartDate(LocalDate.of(2026, 1, 1));
        dto.setRecruitmentEndDate(LocalDate.of(2026, 12, 31));
        return dto;
    }
}
