package com.example.mateon.notification.dto;

import com.example.mateon.notification.domain.Notification;
import lombok.Getter;
import java.time.LocalDateTime;

@Getter
public class NotificationResponseDTO {
    private Long id;
    private String title;
    private String content;
    private String type;      // "APPROVE", "REJECT" 등
    private boolean isRead;
    private LocalDateTime createdAt; // 프론트에서 "4분 전"으로 계산하기 위해 시간 원본 전달

    public NotificationResponseDTO(Notification notification) {
        this.id = notification.getId();
        this.title = notification.getTitle();
        this.content = notification.getContent();
        this.type = notification.getType().name();
        this.isRead = notification.isRead();
        this.createdAt = notification.getCreatedAt();
    }
}