package com.example.mateon.notification.service;

import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.notification.domain.Notification;
import com.example.mateon.notification.dto.NotificationResponseDTO;
import com.example.mateon.notification.event.NotificationCreatedEvent;
import com.example.mateon.notification.repository.EmitterRepository;
import com.example.mateon.notification.repository.NotificationRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final EmitterRepository emitterRepository;
    private final ApplicationEventPublisher eventPublisher;

    // 앞단 nginx 의 proxy_read_timeout 과 맞물리는 값이라 배포 환경에 따라 조정한다.
    // 근거는 application.properties 의 notification.sse-timeout 주석 참고.
    @Value("${notification.sse-timeout:1h}")
    private Duration sseTimeout;

    // -------------------------------------------------------------------------
    // 1. SSE 구독 (연결)
    // -------------------------------------------------------------------------
    /**
     * SSE 구독. 이 메서드는 DB 를 건드리지 않는다 — 의도적이다.
     *
     * <p>예전엔 시작할 때 userRepository.existsById() 로 유저 존재를 확인했다. 그런데 이 요청은
     * emitter 타임아웃(기본 1시간)까지 살아 있는 async 요청이라, 쿼리를 한 번이라도 돌리면 그 커넥션이
     * 요청이 끝날 때까지 풀로 돌아오지 않았다(open-in-view 기본값 true). 접속자 10명이면 풀
     * (기본 10)이 통째로 말라 로그인까지 막혔다.
     *
     * <p>open-in-view=false 로 근본 원인은 막았지만, 검증 자체도 되살릴 이유가 없다. userId 는
     * JwtAuthenticationFilter 가 이미 검증한 토큰에서 나온 값이라 여기서 재확인해도 얻는 게 없다.
     * 실제로 없는 유저면 emitter 는 만들어지되 알림이 안 갈 뿐이고, 그 emitter 는 타임아웃 때
     * 정리된다.
     */
    public SseEmitter subscribe(Long userId) {
        // 1. Emitter 생성 (타임아웃은 notification.sse-timeout)
        SseEmitter emitter = new SseEmitter(sseTimeout.toMillis());

        // 2. 저장소에 저장 (유저 ID를 키로 사용)
        emitterRepository.save(userId, emitter);

        // 3. 콜백 설정 (완료되거나 타임아웃 시 삭제)
        emitter.onCompletion(() -> {
            log.info("SSE 연결 완료 (삭제): {}", userId);
            emitterRepository.deleteById(userId);
        });
        emitter.onTimeout(() -> {
            log.info("SSE 연결 타임아웃 (삭제): {}", userId);
            emitterRepository.deleteById(userId);
        });

        // 4. [중요] 503 에러 방지용 더미 데이터 전송
        // 연결 직후 아무런 데이터도 보내지 않으면 클라이언트에서 재연결을 시도하거나 에러가 날 수 있음
        sendToClient(emitter, userId, "connect", "연결되었습니다. [User ID: " + userId + "]");

        return emitter;
    }

    // -------------------------------------------------------------------------
    // 2. 알림 전송 (DB 저장 + 실시간 발송)
    // -------------------------------------------------------------------------

    /**
     * 알림을 저장한다. 실시간 SSE 전송은 여기서 하지 않고 커밋 이후로 미룬다.
     *
     * <p>예전엔 저장 직후 같은 트랜잭션에서 곧바로 emitter.send() 를 했다. 그런데 끊긴 연결로
     * 보내면 Spring 이 IOException 을 IllegalStateException 으로 감싸 던지는데, 이건 unchecked 라
     * 트랜잭션 경계를 넘어가면서 rollback-only 마킹을 유발했다. 그 결과 방금 저장한 알림이
     * 통째로 롤백됐고, send() 는 호출자 트랜잭션에 합류(REQUIRED)하므로 채팅 메시지 저장이나
     * 팀 가입 승인까지 함께 되돌아갔다.
     *
     * <p>이제 전송은 AFTER_COMMIT 리스너가 맡는다. 전송 실패가 영속화에 닿을 수 없고,
     * 반대로 커밋이 확정된 알림만 나가므로 롤백된 '유령 알림'도 사라진다.
     */
    @Transactional
    public void send(User receiver, String title, String content, Notification.NotificationType type) {
        // DB에 알림 저장 (로그아웃 상태에서도 기록은 남아야 함)
        Notification notification = Notification.builder()
                .receiver(receiver)
                .title(title)
                .content(content)
                .type(type)
                .build();
        notificationRepository.save(notification);

        // DTO 는 트랜잭션 안에서 만들어 넘긴다. 리스너는 트랜잭션 밖이라 엔티티를 넘기면
        // receiver 지연 로딩에서 터진다. id/createdAt 은 IDENTITY + @CreatedDate 라 save() 직후 채워져 있다.
        eventPublisher.publishEvent(
                new NotificationCreatedEvent(receiver.getId(), new NotificationResponseDTO(notification)));
    }

    /**
     * 접속 중(구독 중)인 사용자에게 실시간 전송한다. 커밋 이후 리스너가 호출하므로 트랜잭션이 없다.
     */
    public void push(Long receiverId, NotificationResponseDTO payload) {
        SseEmitter emitter = emitterRepository.get(receiverId);
        if (emitter == null) {
            return; // 접속 중이 아니다. DB 기록은 이미 남았으니 다음 조회 때 보면 된다.
        }
        sendToClient(emitter, receiverId, "notification", payload);
    }

    // 실제 클라이언트로 데이터를 밀어넣는 메서드
    private void sendToClient(SseEmitter emitter, Long userId, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName) // 클라이언트에서 addEventListener로 받을 이름
                    .data(data));
        } catch (Exception e) {
            // IOException 만 잡으면 안 된다 — 끊긴 연결은 IllegalStateException("Failed to send") 으로
            // 감싸여 오고, 이미 완료/타임아웃된 emitter 도 unchecked 예외를 던진다.
            emitterRepository.deleteById(userId);
            // 클라이언트가 창을 닫은 정상적인 상황이라 에러가 아니다.
            log.warn("SSE 전송 실패로 Emitter 제거: {}", userId);
        }
    }

    // -------------------------------------------------------------------------
    // 3. 내 알림 목록 조회 (API 호출용)
    // -------------------------------------------------------------------------
    @Transactional(readOnly = true)
    public List<NotificationResponseDTO> getMyNotifications(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new MateonException(ErrorCode.USER_NOT_FOUND));

        return notificationRepository.findAllByReceiverIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(NotificationResponseDTO::new)
                .collect(Collectors.toList());
    }
}