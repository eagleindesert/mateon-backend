package com.example.mateon.notification;

import com.example.mateon.notification.domain.Notification;
import com.example.mateon.notification.repository.EmitterRepository;
import com.example.mateon.notification.repository.NotificationRepository;
import com.example.mateon.notification.service.NotificationService;
import com.example.mateon.support.IntegrationTestBase;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * "커밋된 알림만 나간다" 는 사슬을 실제 트랜잭션으로 증명하는 유일한 자리다.
 *
 * <p>단위 테스트는 두 조각으로 나뉘어 있다 — 서비스가 이벤트를 <i>발행</i>하는지
 * ({@code NotificationServiceTest}), 리스너가 그 이벤트를 받아 <i>push</i> 하는지
 * ({@code NotificationPushListenerTest}). 하지만 둘을 이어 주는 것은 애노테이션 두 개
 * ({@code @TransactionalEventListener(AFTER_COMMIT)} 와 {@code @Async}) 뿐이고, 그 배선이
 * 끊겨도 두 단위 테스트는 멀쩡히 통과한다. 여기서만 실제로 확인할 수 있다.
 *
 * <p><b>{@code Propagation.NOT_SUPPORTED} 가 이 테스트의 핵심이다.</b>
 * {@link IntegrationTestBase} 는 클래스 레벨 {@code @Transactional} 이라 테스트 메서드가
 * 롤백되는 트랜잭션 안에서 돈다 — 그 안에서는 AFTER_COMMIT 리스너가 <b>영원히 발화하지 않는다</b>.
 * 그대로 두면 "push 가 안 왔다" 는 결과를 보고도 원인을 배선 문제로 오해하거나, 반대로
 * 아무것도 검증하지 못하는 테스트가 통과해 버린다. 그래서 이 메서드만 바깥 트랜잭션에서 빠져
 * 서비스가 자기 트랜잭션을 열고 진짜로 커밋하게 만든다.
 *
 * <p>대가로 롤백이 없으므로 심은 행은 {@code @AfterEach} 에서 손으로 지운다.
 * 그리고 리스너가 {@code @Async} 라 전송은 다른 스레드에서 조금 뒤에 일어나므로 폴링으로 기다린다.
 */
class NotificationAfterCommitIntegrationTest extends IntegrationTestBase {

    @Autowired NotificationService notificationService;
    @Autowired NotificationRepository notificationRepository;
    @Autowired UserRepository userRepository;
    @Autowired EmitterRepository emitterRepository;

    private Long receiverId;

    @AfterEach
    void cleanUp() {
        // 이 테스트는 진짜로 커밋하므로 롤백이 없다. 남은 행을 지우지 않으면 다음 테스트의
        // 전건 단정(예: 알림 목록 개수)이 이유 없이 깨진다.
        if (receiverId != null) {
            notificationRepository.deleteAll(
                    notificationRepository.findAllByReceiverIdOrderByCreatedAtDesc(receiverId));
            userRepository.deleteById(receiverId);
            emitterRepository.deleteById(receiverId);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("커밋이 끝난 뒤에야 SSE 로 밀어준다 — 저장과 전송이 실제로 이어져 있다")
    void pushesAfterCommit() {
        receiverId = givenCommittedUser();
        CountingEmitter emitter = new CountingEmitter();
        emitterRepository.save(receiverId, emitter);

        notificationService.send(userRepository.findById(receiverId).orElseThrow(),
                "가입 승인", "1팀 가입이 승인되었습니다.", Notification.NotificationType.APPROVE);

        // 리스너가 @Async 라 같은 스레드에서 즉시 일어나지 않는다.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(emitter.sendCount.get()).isEqualTo(1));

        // 그리고 그 알림은 DB 에도 남아 있어야 한다 (전송과 저장은 둘 다 성립해야 한다).
        List<Notification> saved =
                notificationRepository.findAllByReceiverIdOrderByCreatedAtDesc(receiverId);
        assertThat(saved).singleElement()
                .extracting(Notification::getTitle).isEqualTo("가입 승인");
    }

    /**
     * 커밋된 유저 한 명. {@code NOT_SUPPORTED} 라 이 저장도 자기 트랜잭션에서 즉시 커밋된다.
     */
    private Long givenCommittedUser() {
        User user = User.builder()
                .email("sse-" + UUID.randomUUID() + "@univ.ac.kr")
                .name("김수신")
                .schoolVerified(true)
                .build();
        return userRepository.save(user).getId();
    }

    /** 전송 횟수만 세는 emitter. 초기화되지 않은 진짜 emitter 는 send 흔적을 남기지 않는다. */
    private static class CountingEmitter extends SseEmitter {
        private final AtomicInteger sendCount = new AtomicInteger();

        @Override
        public void send(SseEventBuilder builder) {
            sendCount.incrementAndGet();
        }
    }
}
