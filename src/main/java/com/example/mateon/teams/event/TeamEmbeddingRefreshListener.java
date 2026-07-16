package com.example.mateon.teams.event;

import com.example.mateon.teams.service.TeamEmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class TeamEmbeddingRefreshListener {

    private final TeamEmbeddingService teamEmbeddingService;

    // 팀 저장 트랜잭션이 커밋된 뒤, 별도 스레드에서 임베딩을 갱신한다.
    //   - AFTER_COMMIT: 커밋 안 된 팀으로 임베딩을 만들지 않는다 (롤백 시 이벤트 무시).
    //   - @Async: AI 호출이 최대 60초라 HTTP 응답을 붙잡으면 안 된다 (클라이언트는 즉시 응답받음).
    //   - 실패는 무시(warn 만)한다. AI 서버 장애가 팀 CRUD 를 막지 않고, 다음 수정 때 재계산된다.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefreshRequested(TeamEmbeddingRefreshRequestedEvent event) {
        try {
            teamEmbeddingService.refresh(event.teamId());
        } catch (Exception e) {
            log.warn("팀 임베딩 갱신 실패: teamId={}", event.teamId(), e);
        }
    }
}
