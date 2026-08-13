package com.example.mateon.notification.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.notification.domain.Notification;
import com.example.mateon.notification.dto.NotificationResponseDTO;
import com.example.mateon.notification.event.NotificationCreatedEvent;
import com.example.mateon.notification.repository.EmitterRepository;
import com.example.mateon.notification.repository.NotificationRepository;
import com.example.mateon.support.TestEntities;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSE 알림의 수명 관리와 저장/전송 분리를 고정한다.
 *
 * <p>이 서비스에는 과거에 서비스를 멈춘 버그가 두 개 묻혀 있고, 지금 코드는 그 둘을 피한 모양이다.
 *
 * <p><b>하나: 저장과 전송을 한 트랜잭션에서 하면 안 된다.</b> 예전엔 저장 직후 곧바로
 * {@code emitter.send()} 를 했는데, 끊긴 연결로 보내면 Spring 이 IOException 을
 * IllegalStateException 으로 감싸 던진다. 이건 unchecked 라 트랜잭션을 rollback-only 로
 * 만들었고, 그 결과 <b>알림뿐 아니라 그 알림을 유발한 채팅 메시지·팀 승인까지 통째로
 * 되돌아갔다</b>. 그래서 {@code send()} 는 저장과 이벤트 발행만 하고 emitter 를 절대 건드리지
 * 않아야 한다 — 이 테스트가 그걸 직접 확인한다.
 *
 * <p><b>둘: 실패한 emitter 는 즉시 버려야 한다.</b> 안 버리면 죽은 연결이 계속 쌓여 매 알림마다
 * 예외 비용을 문다.
 *
 * <p>{@code subscribe()} 가 DB 를 건드리지 않는 것도 의도다 — 1시간짜리 async 요청에서 쿼리를
 * 한 번이라도 돌리면 그 커넥션이 요청이 끝날 때까지 풀로 돌아오지 않아, 접속자 10명이면
 * 커넥션 풀이 통째로 말랐다. 그래서 "유저 존재 확인"을 되살리지 못하도록 못박는다.
 */
class NotificationServiceTest {

    private static final long USER_ID = 1L;

    private NotificationRepository notificationRepository;
    private UserRepository userRepository;
    private EmitterRepository emitterRepository;
    private ApplicationEventPublisher eventPublisher;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        userRepository = mock(UserRepository.class);
        // emitter 저장소는 목이 아니라 진짜를 쓴다 — 축출 여부가 이 클래스의 핵심 관심사다.
        emitterRepository = new EmitterRepository();
        eventPublisher = mock(ApplicationEventPublisher.class);

        notificationService = new NotificationService(
                notificationRepository, userRepository, emitterRepository, eventPublisher);
        // @Value 필드는 손으로 조립한 객체에 주입되지 않는다. 그대로 두면 toMillis() 에서 NPE.
        ReflectionTestUtils.setField(notificationService, "sseTimeout", Duration.ofMinutes(30));
    }

    @Nested
    @DisplayName("구독")
    class Subscribe {

        @Test
        @DisplayName("emitter 를 userId 로 등록하고 설정된 타임아웃을 건다")
        void registersEmitterWithConfiguredTimeout() {
            SseEmitter emitter = notificationService.subscribe(USER_ID);

            assertThat(emitterRepository.get(USER_ID)).isSameAs(emitter);
            assertThat(emitter.getTimeout()).isEqualTo(Duration.ofMinutes(30).toMillis());
        }

        @Test
        @DisplayName("DB 를 전혀 건드리지 않는다 — 1시간짜리 요청이 커넥션을 붙잡으면 풀이 마른다")
        void doesNotTouchDatabase() {
            notificationService.subscribe(USER_ID);

            verify(userRepository, never()).existsById(any());
            verify(userRepository, never()).findById(any());
            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("같은 유저가 다시 구독하면 최신 emitter 로 교체된다 (재연결 시 옛 소켓이 남지 않는다)")
        void resubscribeReplacesEmitter() {
            SseEmitter first = notificationService.subscribe(USER_ID);
            SseEmitter second = notificationService.subscribe(USER_ID);

            assertThat(emitterRepository.get(USER_ID)).isSameAs(second).isNotSameAs(first);
        }
    }

    @Nested
    @DisplayName("저장 (send) — emitter 를 건드리지 않는 것이 핵심이다")
    class Send {

        @Test
        @DisplayName("알림을 저장한다")
        void persists() {
            User receiver = User.builder().id(USER_ID).name("김학생").build();

            notificationService.send(receiver, "가입 승인", "1팀 가입이 승인되었습니다.",
                    Notification.NotificationType.APPROVE);

            ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(captor.capture());
            assertThat(captor.getValue().getTitle()).isEqualTo("가입 승인");
            assertThat(captor.getValue().getType()).isEqualTo(Notification.NotificationType.APPROVE);
            assertThat(captor.getValue().isRead()).isFalse();
        }

        @Test
        @DisplayName("구독 중이어도 이 자리에서 전송하지 않는다 — 전송 실패가 호출자 트랜잭션을 롤백시켰던 버그")
        void neverPushesInline() {
            RecordingEmitter emitter = new RecordingEmitter();
            emitterRepository.save(USER_ID, emitter);

            notificationService.send(User.builder().id(USER_ID).name("김학생").build(),
                    "제목", "내용", Notification.NotificationType.INFO);

            assertThat(emitter.sendCount.get())
                    .as("send() 는 저장과 이벤트 발행만 한다")
                    .isZero();
        }

        @Test
        @DisplayName("이벤트에는 엔티티가 아니라 DTO 를 싣는다 (커밋 후 리스너에서 지연 로딩이 터진다)")
        void publishesDtoNotEntity() {
            User receiver = User.builder().id(USER_ID).name("김학생").build();

            notificationService.send(receiver, "제목", "내용", Notification.NotificationType.INFO);

            ArgumentCaptor<NotificationCreatedEvent> event =
                    ArgumentCaptor.forClass(NotificationCreatedEvent.class);
            verify(eventPublisher).publishEvent(event.capture());

            assertThat(event.getValue().receiverId()).isEqualTo(USER_ID);
            assertThat(event.getValue().payload()).isInstanceOf(NotificationResponseDTO.class);
            assertThat(event.getValue().payload().getTitle()).isEqualTo("제목");
            assertThat(event.getValue().payload().getType()).isEqualTo("INFO");
        }
    }

    @Nested
    @DisplayName("실시간 전송 (push)")
    class Push {

        @Test
        @DisplayName("구독 중이면 notification 이벤트로 보낸다")
        void sendsToSubscriber() {
            RecordingEmitter emitter = new RecordingEmitter();
            emitterRepository.save(USER_ID, emitter);

            notificationService.push(USER_ID, dto());

            assertThat(emitter.sendCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("구독 중이 아니면 조용히 아무것도 하지 않는다 (DB 기록은 이미 남았다)")
        void noOpWhenNotSubscribed() {
            assertThatCode(() -> notificationService.push(USER_ID, dto())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("전송이 실패하면 emitter 를 버리고 예외는 새지 않는다 (창을 닫은 정상 상황이다)")
        void evictsFailedEmitter() {
            emitterRepository.save(USER_ID, new ThrowingEmitter());

            assertThatCode(() -> notificationService.push(USER_ID, dto())).doesNotThrowAnyException();

            assertThat(emitterRepository.get(USER_ID))
                    .as("죽은 연결이 남으면 알림마다 예외 비용을 계속 문다")
                    .isNull();
        }

        @Test
        @DisplayName("IOException 이 아니라 IllegalStateException 으로 감싸여 와도 잡아낸다")
        void handlesUncheckedFailureToo() {
            emitterRepository.save(USER_ID, new IllegalStateEmitter());

            assertThatCode(() -> notificationService.push(USER_ID, dto())).doesNotThrowAnyException();

            assertThat(emitterRepository.get(USER_ID)).isNull();
        }
    }

    @Nested
    @DisplayName("목록 조회")
    class GetMyNotifications {

        @Test
        @DisplayName("저장된 순서를 그대로 DTO 로 옮긴다")
        void mapsInOrder() {
            User receiver = User.builder().id(USER_ID).name("김학생").build();
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(receiver));
            when(notificationRepository.findAllByReceiverIdOrderByCreatedAtDesc(USER_ID))
                    .thenReturn(List.of(notification(receiver, "최근"), notification(receiver, "이전")));

            assertThat(notificationService.getMyNotifications(USER_ID))
                    .extracting(NotificationResponseDTO::getTitle)
                    .containsExactly("최근", "이전");
        }

        @Test
        @DisplayName("없는 유저면 USER_NOT_FOUND (404)")
        void unknownUser() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> notificationService.getMyNotifications(USER_ID))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("알림이 없으면 빈 리스트다")
        void empty() {
            when(userRepository.findById(USER_ID))
                    .thenReturn(Optional.of(User.builder().id(USER_ID).name("김학생").build()));
            when(notificationRepository.findAllByReceiverIdOrderByCreatedAtDesc(USER_ID))
                    .thenReturn(List.of());

            assertThat(notificationService.getMyNotifications(USER_ID)).isEmpty();
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private NotificationResponseDTO dto() {
        return new NotificationResponseDTO(
                notification(User.builder().id(USER_ID).name("김학생").build(), "제목"));
    }

    private Notification notification(User receiver, String title) {
        Notification notification = Notification.builder()
                .receiver(receiver).title(title).content("내용")
                .type(Notification.NotificationType.INFO)
                .build();
        TestEntities.withId(notification, 1L);
        return TestEntities.withField(notification, "createdAt", LocalDateTime.now());
    }

    /**
     * 전송 호출을 세기만 하는 emitter.
     *
     * <p>초기화되지 않은 진짜 {@link SseEmitter} 는 send() 가 버퍼링만 하고 아무 흔적도 남기지
     * 않아서, "보냈는지"를 밖에서 확인할 방법이 없다. 그래서 send 를 가로채는 서브클래스를 쓴다.
     */
    private static class RecordingEmitter extends SseEmitter {
        private final AtomicInteger sendCount = new AtomicInteger();

        @Override
        public void send(SseEventBuilder builder) {
            sendCount.incrementAndGet();
        }
    }

    /** 끊긴 소켓: IOException 을 던진다. */
    private static class ThrowingEmitter extends SseEmitter {
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("Broken pipe");
        }
    }

    /** 이미 완료/타임아웃된 emitter: Spring 이 unchecked 로 감싸 던진다. */
    private static class IllegalStateEmitter extends SseEmitter {
        @Override
        public void send(SseEventBuilder builder) {
            throw new IllegalStateException("ResponseBodyEmitter has already completed");
        }
    }
}
