package com.example.mateon.teams.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.teams.domain.Team;
import com.example.mateon.teams.event.TeamCompletedEvent;
import com.example.mateon.teams.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 팀 활동 종료. 협업 온도 평가가 열리는 유일한 관문이다.
 *
 * <p>종료 경로는 둘이다.
 * <ul>
 *   <li>팀장 수동 종료 — 자율 프로젝트(eventId=null)에는 이 방법뿐이다. 종료 기준 날짜가 없다.
 *   <li>공모전 마감일 경과 자동 종료 — 팀장이 버튼을 안 눌러도 평가가 열리게 하는 폴백.
 * </ul>
 *
 * <p>둘 다 {@link #complete}로 모인다. 종료를 한 곳에서만 하면 "종료됐는데 이벤트가 안 나갔다"가
 * 생길 수 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamCompletionService {

    private final TeamRepository teamRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 팀장의 수동 종료. */
    @Transactional
    public void completeByLeader(Long teamId, Long userId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new MateonException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!team.getLeaderUserId().equals(userId)) {
            throw new MateonException(ErrorCode.FORBIDDEN_ACCESS);
        }
        if (team.isEnded()) {
            throw new MateonException(ErrorCode.TEAM_ALREADY_ENDED);
        }

        complete(team);
    }

    /**
     * 공모전 마감일이 지난 팀을 일괄 종료한다.
     *
     * @return 종료 처리된 팀 수
     */
    @Transactional
    public int completeExpiredTeams(LocalDate today) {
        List<Team> expired = teamRepository.findEndedEventTeamsNotCompleted(today);
        expired.forEach(this::complete);
        return expired.size();
    }

    /** 종료 처리 + 이벤트 발행. 이미 종료된 팀은 호출부에서 걸러진다. */
    private void complete(Team team) {
        team.setEndedAt(LocalDateTime.now());
        // 종료된 팀은 더 이상 모집하지 않는다 (수동 종료 시 모집 중일 수 있다).
        team.setIsRecruiting(false);

        // 커밋 후 팀원 전원에게 평가 요청 알림 (TeamCompletedNotificationListener)
        eventPublisher.publishEvent(new TeamCompletedEvent(team.getId()));
    }
}
