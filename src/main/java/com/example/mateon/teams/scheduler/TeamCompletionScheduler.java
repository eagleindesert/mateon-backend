package com.example.mateon.teams.scheduler;

import com.example.mateon.teams.service.TeamCompletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 공모전 마감일이 지난 팀을 자동 종료한다.
 *
 * <p>팀장이 '활동 종료'를 누르지 않아도 평가가 열리게 하는 폴백이다. 실제로 팀장은 프로젝트가
 * 끝나면 앱을 안 열기 때문에, 이게 없으면 평가 데이터가 거의 쌓이지 않는다.
 *
 * <p>자율 프로젝트(eventId=null)는 대상이 아니다 — 마감일이 없어 종료를 판정할 근거가 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamCompletionScheduler {

    private final TeamCompletionService teamCompletionService;

    @Scheduled(cron = "${collaboration.auto-complete-cron}")
    public void completeExpiredTeams() {
        try {
            int completed = teamCompletionService.completeExpiredTeams(LocalDate.now());
            if (completed > 0) {
                log.info("공모전 마감으로 자동 종료된 팀: {}건", completed);
            }
        } catch (Exception e) {
            // 배치가 죽어도 다음 실행에서 다시 잡힌다 (멱등). 스케줄러 스레드를 죽이지 않는다.
            log.error("팀 자동 종료 배치 실패", e);
        }
    }
}
