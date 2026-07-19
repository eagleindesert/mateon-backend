package com.example.mateon.notification.event;

import com.example.mateon.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 저장이 확정된 알림만 SSE 로 밀어준다.
 *
 * <p>VerificationCodeMailListener 와 같은 패턴이다 — AFTER_COMMIT 이라 저장이 롤백되면 전송도 안 되고,
 * 전송이 실패해도 이미 커밋된 알림에는 영향이 없다.
 *
 * <p>TeamEmbeddingRefreshListener 와 달리 @Async 를 붙이지 않았다. push 는 인메모리 맵 조회 +
 * 소켓 write 로 짧게 끝나고 죽은 소켓은 즉시 실패하므로, 스레드를 하나 더 쓸 이유가 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPushListener {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        try {
            notificationService.push(event.receiverId(), event.payload());
        } catch (Exception e) {
            // push 는 자체적으로 예외를 삼키지만, 여기서 한 번 더 막아 커밋 후 처리 체인이
            // 끊기지 않게 한다 (같은 커밋에 걸린 다른 리스너가 있을 수 있다).
            log.warn("SSE 알림 push 실패: receiverId={}", event.receiverId(), e);
        }
    }
}
