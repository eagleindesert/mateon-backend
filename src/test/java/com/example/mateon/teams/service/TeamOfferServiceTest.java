package com.example.mateon.teams.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.domain.TeamToUserRecommendationItem;
import com.example.mateon.matching.repository.TeamToUserRecommendationLogRepository;
import com.example.mateon.notification.service.NotificationService;
import com.example.mateon.teams.domain.OfferStatus;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamApplication;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.domain.TeamMemberRole;
import com.example.mateon.teams.domain.TeamOffer;
import com.example.mateon.teams.dto.response.TeamOfferResponseDTO;
import com.example.mateon.teams.repository.TeamApplicationRepository;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.teams.repository.TeamOfferRepository;
import com.example.mateon.teams.repository.TeamRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 역제안 발송과 응답 — 지원서 흐름의 거울이지만 <b>승인 주체가 반대</b>다. 팀장이 보내고
 * 유저가 수락하며, 수락되면 팀장의 재승인 없이 그 자리에서 팀원이 된다.
 *
 * <p>
 * 그래서 여기가 실제로 사람이 팀에 들어오는 지점이다. 정원 마감 절차는
 * {@code TeamService.processApplication} 과 <b>글자 그대로 같은 순서</b>여야 한다
 * (멤버 저장 → flush → 집계 → isFullWith). 한쪽만 고치면 두 경로가 다르게 세고, 그 결과는
 * "정원 4명 팀에 5명이 들어와 있다" 로 나타난다. 두 파일 어디를 봐도 버그가 안 보인다 —
 * 각각은 맞기 때문이다.
 *
 * <p>
 * 이쪽만의 위험이 둘 더 있다:
 * <ul>
 * <li><b>시간이 흐른다.</b> 제안을 보낸 뒤 유저가 수락하기까지 며칠이 지날 수 있고, 그 사이에
 * 정원이 차거나 활동이 끝난다. 수락 시점에 팀 상태를 다시 보지 않으면 마감된 팀에
 * 사람이 들어간다.</li>
 * <li><b>AI 점수의 출처.</b> 프론트가 되보낸 값이 아니라 서버가 추천 이력에서 찾아 넣는다.
 * 요청 본문에서 읽도록 "간소화" 하면 팀장이 아무 점수나 박아 넣을 수 있다.</li>
 * </ul>
 *
 * <p>
 * 취소 알림은 {@code TeamOfferServiceNotificationTest} 가 맡는다. 여기서는 상태 전이와
 * 가드, 순서를 본다.
 */
class TeamOfferServiceTest {

    private static final long TEAM_ID = 7L;
    private static final long LEADER_ID = 1L;
    private static final long TARGET_ID = 2L;
    private static final long OFFER_ID = 50L;

    private TeamOfferRepository offerRepository;
    private TeamRepository teamRepository;
    private TeamMemberRepository teamMemberRepository;
    private TeamApplicationRepository applicationRepository;
    private TeamToUserRecommendationLogRepository recommendationLogRepository;
    private UserRepository userRepository;
    private NotificationService notificationService;
    private ApplicationEventPublisher eventPublisher;
    private TeamOfferService service;

    private Team team;
    private User leader;
    private User target;

    @BeforeEach
    void setUp() {
        offerRepository = mock(TeamOfferRepository.class);
        teamRepository = mock(TeamRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        applicationRepository = mock(TeamApplicationRepository.class);
        recommendationLogRepository = mock(TeamToUserRecommendationLogRepository.class);
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);

        eventPublisher = mock(ApplicationEventPublisher.class);

        service = new TeamOfferService(offerRepository, teamRepository, teamMemberRepository,
          applicationRepository, recommendationLogRepository, userRepository,
          notificationService, eventPublisher);

        leader = user(LEADER_ID, "팀장");
        target = user(TARGET_ID, "대상유저");
        team = team();

        when(userRepository.findById(LEADER_ID)).thenReturn(Optional.of(leader));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
    }

    @Nested
    @DisplayName("제안 발송 — 가드")
    class CreateGuards {

        @Test
        @DisplayName("학교 인증 전 팀장은 제안을 보낼 수 없다")
        void requiresSchoolVerification() {
            when(userRepository.findById(LEADER_ID)).thenReturn(
              Optional.of(User.builder().id(LEADER_ID).schoolVerified(false).build()));

            assertThatThrownBy(() -> service.createOffer(TEAM_ID, TARGET_ID, "함께해요", LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.SCHOOL_NOT_VERIFIED);

            verify(offerRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("팀장이 아니면 보낼 수 없다")
        void requiresLeader() {
            when(userRepository.findById(9L)).thenReturn(Optional.of(user(9L, "남")));

            assertThatThrownBy(() -> service.createOffer(TEAM_ID, TARGET_ID, "함께해요", 9L))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN_ACCESS);
        }

        @Test
        @DisplayName("모집이 닫혔거나 활동이 끝난 팀은 제안을 보낼 수 없다 (두 축을 모두 본다)")
        void requiresOpenTeam() {
            team.setIsRecruiting(false);

            assertThatThrownBy(() -> service.createOffer(TEAM_ID, TARGET_ID, "함께해요", LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.TEAM_RECRUITMENT_CLOSED);

            team.setIsRecruiting(true);
            team.setEndedAt(LocalDateTime.now());

            assertThatThrownBy(() -> service.createOffer(TEAM_ID, TARGET_ID, "함께해요", LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.TEAM_RECRUITMENT_CLOSED);
        }

        @Test
        @DisplayName("자기 자신에게는 제안할 수 없다")
        void cannotOfferSelf() {
            assertThatThrownBy(() -> service.createOffer(TEAM_ID, LEADER_ID, "함께해요", LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        }

        @Test
        @DisplayName("이미 팀원인 사람에게는 제안하지 않는다 (userId 를 직접 넣는 호출도 막는다)")
        void alreadyMember() {
            when(teamMemberRepository.existsByTeamIdAndUserIdAndLeftAtIsNull(TEAM_ID, TARGET_ID))
              .thenReturn(true);

            assertThatThrownBy(() -> service.createOffer(TEAM_ID, TARGET_ID, "함께해요", LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
        }

        @Test
        @DisplayName("이미 지원서를 낸 사람에게도 제안하지 않는다")
        void alreadyApplied() {
            when(applicationRepository.findByTeamIdAndApplicantId(TEAM_ID, TARGET_ID))
              .thenReturn(Optional.of(TeamApplication.builder().build()));

            assertThatThrownBy(() -> service.createOffer(TEAM_ID, TARGET_ID, "함께해요", LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
        }

        @Test
        @DisplayName("같은 상대에게 두 번 제안하지 않는다 (거절당한 뒤 다시 보내는 것도 막힌다)")
        void alreadyOffered() {
            when(offerRepository.existsByTeamIdAndTargetUserId(TEAM_ID, TARGET_ID)).thenReturn(true);

            assertThatThrownBy(() -> service.createOffer(TEAM_ID, TARGET_ID, "함께해요", LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
        }

        /**
         * 위의 중복 검사와 INSERT 사이에 같은 제안이 들어오면 유니크 인덱스가 막는다. 그
         * {@code DataIntegrityViolationException} 을 그대로 흘리면 프론트는 500 을 받는데,
         * 실제로는 "이미 보냈다" 는 평범한 상황이다.
         */
        @Test
        @DisplayName("경합으로 유니크 위반이 나도 500 이 아니라 DUPLICATE_RESOURCE 다")
        void raceConditionBecomesDuplicate() {
            when(offerRepository.saveAndFlush(any()))
              .thenThrow(new DataIntegrityViolationException("uq_team_offers_pair"));

            assertThatThrownBy(() -> service.createOffer(TEAM_ID, TARGET_ID, "함께해요", LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_RESOURCE);

            verify(notificationService, never()).send(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("제안 발송 — AI 점수의 출처")
    class AiScoreSource {

        @Test
        @DisplayName("점수와 근거는 서버가 추천 이력에서 찾아 넣는다 (프론트가 보낸 값이 아니다)")
        void comesFromRecommendationLog() {
            when(recommendationLogRepository.findLatestItem(TEAM_ID, TARGET_ID))
              .thenReturn(Optional.of(recommendationItem(0.91, "역할이 맞습니다")));

            service.createOffer(TEAM_ID, TARGET_ID, "함께해요", LEADER_ID);

            TeamOffer saved = capturedOffer();
            assertThat(saved.getAiScore()).isEqualTo(0.91);
            assertThat(saved.getAiLabel()).isEqualTo("역할이 맞습니다");
        }

        @Test
        @DisplayName("추천을 거치지 않은 제안은 점수·근거가 null 이고, 그건 정상이다")
        void nullWhenNotRecommended() {
            when(recommendationLogRepository.findLatestItem(TEAM_ID, TARGET_ID))
              .thenReturn(Optional.empty());

            service.createOffer(TEAM_ID, TARGET_ID, "함께해요", LEADER_ID);

            TeamOffer saved = capturedOffer();
            assertThat(saved.getAiScore()).isNull();
            assertThat(saved.getAiLabel()).isNull();
        }

        @Test
        @DisplayName("저장된 제안은 PENDING 이고 응답 시각이 비어 있다")
        void startsPending() {
            service.createOffer(TEAM_ID, TARGET_ID, "함께해요", LEADER_ID);

            TeamOffer saved = capturedOffer();
            assertThat(saved.getStatus()).isEqualTo(OfferStatus.PENDING);
            assertThat(saved.isPending()).isTrue();
            assertThat(saved.getRespondedAt()).isNull();
            assertThat(saved.getMessage()).isEqualTo("함께해요");
        }
    }

    @Nested
    @DisplayName("수락 — 여기서 사람이 실제로 팀에 들어온다")
    class Accept {

        @Test
        @DisplayName("상태가 ACCEPTED 가 되고 MEMBER 행이 생긴다")
        void createsMemberRow() {
            TeamOffer offer = givenPendingOffer();
            when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, TARGET_ID))
              .thenReturn(Optional.empty());

            service.respond(OFFER_ID, true, TARGET_ID);

            assertThat(offer.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
            assertThat(offer.getRespondedAt()).isNotNull();

            ArgumentCaptor<TeamMember> member = ArgumentCaptor.forClass(TeamMember.class);
            verify(teamMemberRepository).save(member.capture());
            assertThat(member.getValue().getRole()).isEqualTo(TeamMemberRole.MEMBER);
            assertThat(member.getValue().getUser().getId()).isEqualTo(TARGET_ID);
        }

        @Test
        @DisplayName("나갔던 멤버는 새 행 대신 leftAt 을 지워 되살린다 (지원서 승인과 같은 규칙)")
        void reactivatesInsteadOfInserting() {
            givenPendingOffer();
            TeamMember left = TeamMember.of(team, target, TeamMemberRole.MEMBER);
            left.setLeftAt(LocalDateTime.now().minusDays(3));
            when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, TARGET_ID))
              .thenReturn(Optional.of(left));

            service.respond(OFFER_ID, true, TARGET_ID);

            assertThat(left.getLeftAt()).isNull();
            verify(teamMemberRepository, never()).save(any());
        }

        /**
         * {@code TeamService.processApplication} 과 <b>같은 순서</b>여야 한다. 두 경로가 다르게
         * 세면 정원을 넘겨 받는 팀이 생긴다.
         */
        @Test
        @DisplayName("flush 가 인원 집계보다 먼저다 — 지원서 승인 경로와 순서가 같아야 한다")
        void flushBeforeCount() {
            givenPendingOffer();
            when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, TARGET_ID))
              .thenReturn(Optional.empty());
            when(teamMemberRepository.countByTeamIdAndLeftAtIsNull(TEAM_ID)).thenReturn(4);

            service.respond(OFFER_ID, true, TARGET_ID);

            InOrder order = inOrder(teamMemberRepository);
            order.verify(teamMemberRepository).save(any());
            order.verify(teamMemberRepository).flush();
            order.verify(teamMemberRepository).countByTeamIdAndLeftAtIsNull(TEAM_ID);
        }

        @Test
        @DisplayName("정원이 차면 모집이 마감된다")
        void closesRecruitingWhenFull() {
            givenPendingOffer();
            when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, TARGET_ID))
              .thenReturn(Optional.empty());
            when(teamMemberRepository.countByTeamIdAndLeftAtIsNull(TEAM_ID)).thenReturn(4);

            service.respond(OFFER_ID, true, TARGET_ID);

            assertThat(team.getIsRecruiting()).isFalse();
        }

        /**
         * 제안을 보낸 뒤 유저가 수락하기까지 며칠이 지날 수 있다. 그 사이에 다른 사람들이
         * 들어와 정원이 찼다면 수락은 실패해야 한다 — 이 재확인이 없으면 정원을 넘긴다.
         */
        @Test
        @DisplayName("보낸 뒤 팀이 마감됐으면 수락할 수 없다 (수락 시점에 다시 확인한다)")
        void closedSinceOfferWasSent() {
            TeamOffer offer = givenPendingOffer();
            team.setIsRecruiting(false);

            assertThatThrownBy(() -> service.respond(OFFER_ID, true, TARGET_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.TEAM_RECRUITMENT_CLOSED);

            assertThat(offer.getStatus()).isEqualTo(OfferStatus.PENDING);
            verify(teamMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("보낸 뒤 활동이 끝났어도 수락할 수 없다")
        void endedSinceOfferWasSent() {
            givenPendingOffer();
            team.setEndedAt(LocalDateTime.now());

            assertThatThrownBy(() -> service.respond(OFFER_ID, true, TARGET_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.TEAM_RECRUITMENT_CLOSED);
        }

        @Test
        @DisplayName("학교 인증을 안 한 유저는 수락할 수 없다 (지원과 같은 규칙)")
        void requiresSchoolVerification() {
            User unverified = User.builder().id(TARGET_ID).name("미인증").schoolVerified(false).build();
            givenPendingOffer(unverified);

            assertThatThrownBy(() -> service.respond(OFFER_ID, true, TARGET_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.SCHOOL_NOT_VERIFIED);

            verify(teamMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("팀장 계정이 사라져도 수락 자체는 성립한다 (알림만 건너뛴다)")
        void acceptsWithoutLeaderAccount() {
            givenPendingOffer();
            when(userRepository.findById(LEADER_ID)).thenReturn(Optional.empty());
            when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, TARGET_ID))
              .thenReturn(Optional.empty());

            TeamOfferResponseDTO responded = service.respond(OFFER_ID, true, TARGET_ID);

            assertThat(responded.getLeaderName()).isNull();
            verify(notificationService, never()).send(any(), any(), any(), any());
        }

        @Test
        @DisplayName("이미 응답한 제안은 다시 응답할 수 없다")
        void alreadyResponded() {
            TeamOffer offer = givenPendingOffer();
            offer.reject();
            when(teamMemberRepository.findByTeamIdAndUserId(anyLong(), anyLong()))
              .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.respond(OFFER_ID, true, TARGET_ID))
              .isInstanceOf(MateonException.class);

            assertThat(offer.getStatus()).isEqualTo(OfferStatus.REJECTED);
            verify(teamMemberRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("거절")
    class Reject {

        @Test
        @DisplayName("상태만 REJECTED 가 되고 멤버 행은 만들지 않는다")
        void touchesNoMembership() {
            TeamOffer offer = givenPendingOffer();

            service.respond(OFFER_ID, false, TARGET_ID);

            assertThat(offer.getStatus()).isEqualTo(OfferStatus.REJECTED);
            verify(teamMemberRepository, never()).save(any());
            verify(teamMemberRepository, never()).flush();
        }

        @Test
        @DisplayName("팀장 계정이 사라졌어도 거절 자체는 성립한다 (알림만 건너뛴다)")
        void worksWithoutLeaderAccount() {
            TeamOffer offer = givenPendingOffer();
            when(userRepository.findById(LEADER_ID)).thenReturn(Optional.empty());

            TeamOfferResponseDTO responded = service.respond(OFFER_ID, false, TARGET_ID);

            assertThat(offer.getStatus()).isEqualTo(OfferStatus.REJECTED);
            assertThat(responded.getLeaderName()).isNull();
            verify(notificationService, never()).send(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("권한과 조회")
    class AuthorizationAndQueries {

        @Test
        @DisplayName("남에게 온 제안에는 응답할 수 없다")
        void onlyTargetCanRespond() {
            TeamOffer offer = givenPendingOffer();

            assertThatThrownBy(() -> service.respond(OFFER_ID, true, 9L))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN_ACCESS);

            assertThat(offer.getStatus()).isEqualTo(OfferStatus.PENDING);
        }

        @Test
        @DisplayName("없는 제안은 RESOURCE_NOT_FOUND 다")
        void missingOffer() {
            when(offerRepository.findById(OFFER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.respond(OFFER_ID, true, TARGET_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("팀의 제안 목록은 팀장만 볼 수 있다")
        void teamOffersRequireLeader() {
            assertThatThrownBy(() -> service.getTeamOffers(TEAM_ID, TARGET_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN_ACCESS);

            verify(offerRepository, never()).findByTeamIdOrderByCreatedAtDesc(anyLong());
        }

        @Test
        @DisplayName("팀장은 그 팀이 보낸 제안을 시간 역순으로 본다")
        void teamOffersReturnMappedRows() {
            TeamOffer offer = offer(team, target);
            when(offerRepository.findByTeamIdOrderByCreatedAtDesc(TEAM_ID))
              .thenReturn(List.of(offer));

            List<TeamOfferResponseDTO> offers = service.getTeamOffers(TEAM_ID, LEADER_ID);

            assertThat(offers).hasSize(1);
            assertThat(offers.get(0).getTeamId()).isEqualTo(TEAM_ID);
            assertThat(offers.get(0).getTargetUserId()).isEqualTo(TARGET_ID);
            assertThat(offers.get(0).getTargetUserName()).isEqualTo("대상유저");
            assertThat(offers.get(0).getLeaderName()).isEqualTo("팀장");
        }

        @Test
        @DisplayName("없는 팀은 RESOURCE_NOT_FOUND 다")
        void teamOffersMissingTeam() {
            when(teamRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTeamOffers(99L, LEADER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }

        /**
         * 제안마다 팀장을 조회하면 받은 제안 화면에서 N+1 이 된다. 서로 다른 팀에서 온 제안
         * 두 건을 주고 조회가 <b>한 번</b>만 나가는지 본다.
         */
        @Test
        @DisplayName("받은 제안 목록은 팀장 이름을 한 번에 모아 온다 (N+1 방지)")
        void myOffersBatchesLeaderLookup() {
            Team otherTeam = new Team();
            otherTeam.setId(8L);
            otherTeam.setTitle("다른 팀");
            otherTeam.setLeaderUserId(3L);

            when(offerRepository.findByTargetUserIdOrderByCreatedAtDesc(TARGET_ID))
              .thenReturn(List.of(offer(team, target), offer(otherTeam, target)));
            when(userRepository.findAllById(any()))
              .thenReturn(List.of(leader, user(3L, "다른팀장")));

            List<TeamOfferResponseDTO> offers = service.getMyOffers(TARGET_ID);

            assertThat(offers).extracting(TeamOfferResponseDTO::getLeaderName)
              .containsExactly("팀장", "다른팀장");
            verify(userRepository, times(1)).findAllById(any());
            verify(userRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("팀장 계정이 사라진 제안은 이름만 비고 나머지는 정상 표시된다")
        void missingLeaderLeavesNameNull() {
            when(offerRepository.findByTargetUserIdOrderByCreatedAtDesc(TARGET_ID))
              .thenReturn(List.of(offer(team, target)));
            when(userRepository.findAllById(any())).thenReturn(List.of());

            List<TeamOfferResponseDTO> offers = service.getMyOffers(TARGET_ID);

            assertThat(offers).hasSize(1);
            assertThat(offers.get(0).getLeaderName()).isNull();
            assertThat(offers.get(0).getTeamTitle()).isEqualTo("테스트 팀");
        }
    }

    // --- 픽스처 -------------------------------------------------------------
    private TeamOffer givenPendingOffer() {
        return givenPendingOffer(target);
    }

    private TeamOffer givenPendingOffer(User targetUser) {
        TeamOffer offer = offer(team, targetUser);
        when(offerRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer));
        return offer;
    }

    private TeamOffer offer(Team ofTeam, User targetUser) {
        TeamOffer offer = new TeamOffer(ofTeam, targetUser, "함께해요", null, null);
        ReflectionTestUtils.setField(offer, "id", OFFER_ID);
        return offer;
    }

    private TeamOffer capturedOffer() {
        ArgumentCaptor<TeamOffer> captor = ArgumentCaptor.forClass(TeamOffer.class);
        verify(offerRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private TeamToUserRecommendationItem recommendationItem(double score, String label) {
        TeamToUserRecommendationItem item = new TeamToUserRecommendationItem();
        ReflectionTestUtils.setField(item, "score", score);
        ReflectionTestUtils.setField(item, "label", label);
        return item;
    }

    private Team team() {
        Team created = new Team();
        created.setId(TEAM_ID);
        created.setTitle("테스트 팀");
        created.setLeaderUserId(LEADER_ID);
        created.setCapacity(4);
        return created;
    }

    private User user(long id, String name) {
        return User.builder().id(id).name(name).schoolVerified(true).build();
    }
}
