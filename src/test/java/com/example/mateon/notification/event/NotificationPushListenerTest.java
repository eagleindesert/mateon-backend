package com.example.mateon.notification.event;

import com.example.mateon.notification.domain.Notification;
import com.example.mateon.notification.dto.NotificationResponseDTO;
import com.example.mateon.notification.service.NotificationService;
import com.example.mateon.support.TestEntities;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 커밋 이후 SSE 푸시 리스너.
 *
 * <p>이 리스너는 {@code @Async} 스레드에서 돈다. 거기서 예외가 새면 그 스레드의 기본 핸들러로
 * 올라가 스택트레이스만 남기고, 같은 커밋에 걸린 다른 AFTER_COMMIT 리스너의 실행을 방해할 수
 * 있다. 그래서 <b>어떤 실패도 밖으로 던지지 않는</b> 것이 이 클래스의 유일한 계약이다.
 * (전송 자체의 실패는 {@link NotificationService#push} 가 이미 삼키지만, 여기서 한 겹 더 막는다.)
 *
 * <p>애노테이션 배선({@code AFTER_COMMIT} 인지, {@code @Async} 인지)은 여기서 리플렉션으로
 * 확인하지 않는다 — 실제로 그 순서가 지켜지는지는 {@code NotificationAfterCommitIntegrationTest}
 * 가 진짜 트랜잭션으로 증명한다. 이 클래스는 동작만 본다.
 */
class NotificationPushListenerTest {

    private NotificationService notificationService;
    private NotificationPushListener listener;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        listener = new NotificationPushListener(notificationService);
    }

    @Test
    @DisplayName("이벤트의 수신자와 페이로드를 그대로 push 에 넘긴다")
    void delegatesToPush() {
        NotificationResponseDTO payload = payload();

        listener.onNotificationCreated(new NotificationCreatedEvent(7L, payload));

        verify(notificationService).push(7L, payload);
    }

    @Test
    @DisplayName("push 가 터져도 예외를 밖으로 내보내지 않는다 (비동기 스레드가 죽으면 안 된다)")
    void swallowsFailures() {
        doThrow(new RuntimeException("emitter 폭발"))
                .when(notificationService).push(anyLong(), any());

        assertThatCode(() -> listener.onNotificationCreated(new NotificationCreatedEvent(7L, payload())))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Error 가 아닌 어떤 RuntimeException 이든 동일하게 삼킨다")
    void swallowsAnyRuntimeException() {
        doThrow(new IllegalStateException("이미 완료된 emitter"))
                .when(notificationService).push(anyLong(), any());

        assertThatCode(() -> listener.onNotificationCreated(new NotificationCreatedEvent(7L, payload())))
                .doesNotThrowAnyException();

        Mockito.verify(notificationService).push(anyLong(), any());
    }

    private NotificationResponseDTO payload() {
        Notification notification = Notification.builder()
                .receiver(User.builder().id(7L).name("김학생").build())
                .title("제목").content("내용")
                .type(Notification.NotificationType.INFO)
                .build();
        TestEntities.withId(notification, 1L);
        TestEntities.withField(notification, "createdAt", LocalDateTime.now());
        return new NotificationResponseDTO(notification);
    }
}
