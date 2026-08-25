package com.example.mateon.teams.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.repository.TeamToUserRecommendationLogRepository;
import com.example.mateon.notification.domain.Notification;
import com.example.mateon.notification.service.NotificationService;
import com.example.mateon.teams.domain.OfferStatus;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamOffer;
import com.example.mateon.teams.repository.TeamApplicationRepository;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.teams.repository.TeamOfferRepository;
import com.example.mateon.teams.repository.TeamRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 역제안 회수(cancelOffer)가 대상 유저에게 알려지는지, 그리고 <b>언제 알리지 않는지</b>를 고정한다.
 *
 * <p>
 * 제안을 받은 유저에게는 "팀 제안 도착" 알림이 이미 가 있다. 그런데 팀장이 회수하면 예전에는
 * 아무 신호도 가지 않아, 유저는 목록에 다시 들어가 CANCELED 를 보고서야 알 수 있었다.
 *
 * <p>
 * 더 중요한 건 발송 <b>시점</b>이다. send 를 offer.cancel() 앞에 두면 이미 수락하거나 거절한
 * 제안에도 "취소되었습니다" 가 나간다 — 예외로 트랜잭션이 롤백되면 알림 저장도 함께 되돌아가지만,
 * 그건 우연히 맞는 것이지 규칙이 아니다. 아래 두 번째 테스트가 그 순서를 잠근다.
 */
class TeamOfferServiceNotificationTest {

    private static final long OFFER_ID = 11L;
    private static final long TEAM_ID = 7L;
    private static final long LEADER_ID = 1L;
    private static final long TARGET_ID = 2L;
    private static final String TEAM_TITLE = "역제안 테스트 팀";

    private TeamOfferRepository offerRepository;
    private NotificationService notificationService;
    private TeamOfferService service;

    private Team team;
    private User target;

    @BeforeEach
    void setUp() {
        offerRepository = mock(TeamOfferRepository.class);
        notificationService = mock(NotificationService.class);

        service = new TeamOfferService(offerRepository, mock(TeamRepository.class),
          mock(TeamMemberRepository.class), mock(TeamApplicationRepository.class),
          mock(TeamToUserRecommendationLogRepository.class), mock(UserRepository.class),
          notificationService);

        team = new Team();
        team.setId(TEAM_ID);
        team.setTitle(TEAM_TITLE);
        team.setLeaderUserId(LEADER_ID);

        target = User.builder().id(TARGET_ID).name("제안받은사람").schoolVerified(true).build();
    }

    @Test
    @DisplayName("제안을 회수하면 대상 유저에게 알림이 간다")
    void notifiesTargetOnCancel() {
        givenOffer(pendingOffer());

        service.cancelOffer(OFFER_ID, LEADER_ID);

        ArgumentCaptor<User> receiver = ArgumentCaptor.forClass(User.class);
        verify(notificationService).send(receiver.capture(), eq("제안 취소"),
          contains(TEAM_TITLE), eq(Notification.NotificationType.INFO));
        assertThat(receiver.getValue().getId()).isEqualTo(TARGET_ID);
    }

    @Test
    @DisplayName("이미 수락된 제안은 회수가 막히고 취소 알림도 나가지 않는다")
    void silentWhenAlreadyResponded() {
        TeamOffer accepted = pendingOffer();
        accepted.accept();
        givenOffer(accepted);

        assertThatThrownBy(() -> service.cancelOffer(OFFER_ID, LEADER_ID))
          .isInstanceOf(MateonException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OFFER_ALREADY_RESPONDED);

        // send 가 cancel() 앞에 있었다면 여기서 알림이 하나 잡힌다.
        verify(notificationService, never()).send(any(), anyString(), anyString(), any());
        assertThat(accepted.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
    }

    @Test
    @DisplayName("팀장이 아닌 사람이 회수를 시도하면 알림 없이 차단된다")
    void silentWhenNotLeader() {
        givenOffer(pendingOffer());

        assertThatThrownBy(() -> service.cancelOffer(OFFER_ID, 999L))
          .isInstanceOf(MateonException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN_ACCESS);

        verify(notificationService, never()).send(any(), anyString(), anyString(), any());
    }

    // ── 준비 헬퍼 ────────────────────────────────────────────────────────────
    private TeamOffer pendingOffer() {
        return new TeamOffer(team, target, "함께해요", null, null);
    }

    private void givenOffer(TeamOffer offer) {
        when(offerRepository.findById(OFFER_ID)).thenReturn(Optional.of(offer));
    }
}
