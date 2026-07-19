package com.example.mateon.notification.event;

import com.example.mateon.notification.dto.NotificationResponseDTO;

/**
 * 알림이 DB 에 저장됐다. 실시간 SSE 전송은 이 이벤트를 받아 커밋 이후에 한다.
 *
 * <p>엔티티가 아니라 DTO 를 싣는 이유: 수신 리스너는 트랜잭션 밖(AFTER_COMMIT)에서 돌기 때문에
 * 엔티티를 넘기면 {@code Notification.receiver} 지연 로딩에서 터진다. 저장 직후 트랜잭션 안에서
 * 만들어 넘기면 안전하다.
 */
public record NotificationCreatedEvent(Long receiverId, NotificationResponseDTO payload) {
}
