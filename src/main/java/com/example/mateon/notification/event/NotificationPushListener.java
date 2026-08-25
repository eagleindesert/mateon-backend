package com.example.mateon.notification.event;

import com.example.mateon.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 저장이 확정된 알림만 SSE 로 밀어준다.
 *
 * <p>
 * VerificationCodeMailListener 와 같은 패턴이다 — AFTER_COMMIT 이라 저장이 롤백되면 전송도 안 되고,
 * 전송이 실패해도 이미 커밋된 알림에는 영향이 없다.
 *
 * <p>
 * TeamEmbeddingRefreshListener 처럼 @Async 로 별도 스레드에서 보낸다. "죽은 소켓은 즉시
 * 실패하니 짧게 끝난다"는 가정이 틀렸기 때문이다 — 끊긴(RST) 소켓은 즉시 실패하지만, 응답이
 * 없는(모바일이 터널 밖으로 나간) 소켓은 write 가 블로킹된다. AFTER_COMMIT 은 커밋 직후지만
 * 아직 트랜잭션 정리 전이라, 그 자리에서 멈추면 요청 스레드와 자원이 함께 묶인다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPushListener {

    private final NotificationService notificationService;

    @Async
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
