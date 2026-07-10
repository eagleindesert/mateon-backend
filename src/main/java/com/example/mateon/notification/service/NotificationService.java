package com.example.mateon.notification.service;

import com.example.mateon.common.exception.MateonException;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.notification.domain.Notification;
import com.example.mateon.notification.dto.NotificationResponseDTO;
import com.example.mateon.notification.repository.EmitterRepository;
import com.example.mateon.notification.repository.NotificationRepository;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final EmitterRepository emitterRepository;

    // -------------------------------------------------------------------------
    // 1. SSE 구독 (연결)
    // -------------------------------------------------------------------------
    public SseEmitter subscribe(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new MateonException(ErrorCode.USER_NOT_FOUND);
        }

        // 1. Emitter 생성 (타임아웃 1시간 설정)
        SseEmitter emitter = new SseEmitter(60L * 1000 * 60);

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
    @Transactional
    public void send(User receiver, String title, String content, Notification.NotificationType type) {
        // A. DB에 알림 저장 (로그아웃 상태에서도 기록은 남아야 함)
        Notification notification = Notification.builder()
                .receiver(receiver)
                .title(title)
                .content(content)
                .type(type)
                .build();
        notificationRepository.save(notification);

        // B. 사용자가 접속 중(구독 중)이라면 실시간 전송
        SseEmitter emitter = emitterRepository.get(receiver.getId());
        if (emitter != null) {
            NotificationResponseDTO responseDto = new NotificationResponseDTO(notification);
            sendToClient(emitter, receiver.getId(), "notification", responseDto);
        }
    }

    // 실제 클라이언트로 데이터를 밀어넣는 메서드
    private void sendToClient(SseEmitter emitter, Long userId, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName) // 클라이언트에서 addEventListener로 받을 이름
                    .data(data));
        } catch (IOException e) {
            // 전송 중 에러가 나면(클라이언트가 끊김 등) Emitter 제거
            emitterRepository.deleteById(userId);
            log.error("SSE 전송 오류로 인한 Emitter 제거: {}", userId);
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