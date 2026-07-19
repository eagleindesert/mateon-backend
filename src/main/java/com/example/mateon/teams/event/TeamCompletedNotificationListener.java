package com.example.mateon.teams.event;

import com.example.mateon.notification.domain.Notification;
import com.example.mateon.notification.service.NotificationService;
import com.example.mateon.teams.config.CollaborationProperties;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.domain.TeamMember;
import com.example.mateon.teams.repository.TeamMemberRepository;
import com.example.mateon.teams.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 팀 종료 후 팀원 전원에게 "평가를 남겨달라"고 알린다.
 *
 * <p>TeamEmbeddingRefreshListener 와 같은 패턴이다 — AFTER_COMMIT 이라 종료가 롤백되면
 * 알림도 안 나가고, @Async 라 알림 발송이 종료 API 응답을 붙잡지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamCompletedNotificationListener {

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final NotificationService notificationService;
    private final CollaborationProperties properties;

    // REQUIRES_NEW 인 이유: AFTER_COMMIT 리스너는 원래 트랜잭션이 이미 끝난 뒤에 돈다.
    // 그 상태에서 @Transactional(기본 REQUIRED) 을 붙이면 스프링이 부팅 시점에 거부한다.
    // 알림 저장과 지연 로딩(TeamMember.user)을 하려면 트랜잭션이 필요하므로 새로 연다.
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTeamCompleted(TeamCompletedEvent event) {
        try {
            Team team = teamRepository.findById(event.teamId()).orElse(null);
            if (team == null) {
                return; // 종료 직후 삭제된 팀. 알릴 대상이 없다.
            }

            List<TeamMember> members = teamMemberRepository.findByTeamIdAndLeftAtIsNull(event.teamId());

            // 혼자인 팀은 평가할 상대가 없다 — 알림이 소음만 된다.
            if (members.size() < 2) {
                return;
            }

            String content = String.format(
                    "[%s] 활동이 종료되었습니다. %d일 안에 팀원 평가를 남겨주세요.",
                    team.getTitle(), properties.getReviewWindowDays());

            for (TeamMember member : members) {
                notificationService.send(member.getUser(), "팀원 평가 요청", content,
                        Notification.NotificationType.INFO);
            }
        } catch (Exception e) {
            // 알림 실패가 종료 처리를 되돌리게 두지 않는다 (이미 커밋됐고, 평가는 알림 없이도 가능하다).
            log.warn("팀 종료 알림 발송 실패: teamId={}", event.teamId(), e);
        }
    }
}
