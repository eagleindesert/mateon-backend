package com.example.mateon.matching.event;

import com.example.mateon.matching.service.MatchingIntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingIntentReextractListener {

    private final MatchingIntentService matchingIntentService;

    /**
     * 프로필 저장이 커밋된 뒤 별도 스레드에서 재추출한다. AI 가 최대 60 초라 PUT 응답을
     * 붙잡으면 안 되고, 실패해도 기존 슬롯·벡터가 남는다.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReextractRequested(MatchingIntentReextractRequestedEvent event) {
        try {
            matchingIntentService.reextractCompleted(event.userId());
        } catch (Exception e) {
            log.warn("매칭 의도 재추출 실패: userId={}", event.userId(), e);
        }
    }
}
