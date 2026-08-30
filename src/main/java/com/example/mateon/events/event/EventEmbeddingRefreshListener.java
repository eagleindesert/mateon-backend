package com.example.mateon.events.event;

import com.example.mateon.events.service.EventEmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventEmbeddingRefreshListener {

    private final EventEmbeddingService eventEmbeddingService;

    // 활동 저장 트랜잭션이 커밋된 뒤, 별도 스레드에서 임베딩을 갱신한다.
    //   - AFTER_COMMIT: 커밋 안 된 활동으로 임베딩을 만들지 않는다 (롤백 시 이벤트 무시).
    //   - @Async: AI 호출이 최대 수십 초라 HTTP 응답을 붙잡으면 안 된다.
    //   - contestEmbeddingExecutor: 크롤러가 한 번에 수백 건을 넣어도 AI 를 동시에 때리지 않는다.
    //   - 실패는 무시(warn 만)한다. AI 서버 장애가 활동 등록을 막지 않고, 백필/다음 시도에서 재계산된다.
    @Async("contestEmbeddingExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefreshRequested(EventEmbeddingRefreshRequestedEvent event) {
        try {
            eventEmbeddingService.refresh(event.eventId());
        } catch (Exception e) {
            log.warn("공모전 임베딩 갱신 실패: eventId={}", event.eventId(), e);
        }
    }
}
