package com.example.mateon.notification.domain;

import com.example.mateon.user.domain.User;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Notification {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User receiver; // 알림을 받는 사람 (지원자)

    private String title;   // 예: "가입승인", "가입거절"
    private String content; // 예: "[데분 캠프] 1팀 가입이 승인되었습니다."

    @Enumerated(EnumType.STRING)
    private NotificationType type; // 프론트에서 아이콘 구분용 (APPROVE, REJECT, ETC)

    private boolean isRead; // 읽음 여부

    @CreatedDate
    private LocalDateTime createdAt; // 생성 시간 (4분 전 계산용)

    @Builder
    public Notification(User receiver, String title, String content, NotificationType type) {
        this.receiver = receiver;
        this.title = title;
        this.content = content;
        this.type = type;
        this.isRead = false;
    }

    public enum NotificationType {
        APPROVE, // 파란 체크 아이콘
        REJECT,  // 빨간 X 아이콘
        INFO     // 일반 알림
    }
}