package com.example.mateon.notification.dto;

import com.example.mateon.notification.domain.Notification;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import java.time.LocalDateTime;

@Schema(description = "알림 한 건. 목록 조회와 SSE 실시간 수신이 같은 형태를 쓴다.")
@Getter
public class NotificationResponseDTO {

    private Long id;
    private String title;
    private String content;
    @Schema(description = "알림 종류. 아이콘을 가르는 데 쓴다 — APPROVE 는 파란 체크, REJECT 는 빨간 X, INFO 는 일반.",
      allowableValues = {"APPROVE", "REJECT", "INFO"})
    private String type;      // "APPROVE", "REJECT" 등
    @Schema(description = "읽음 여부. **JSON 키는 `read`** 다 — 필드명이 is- 로 시작해 Jackson 이 접두어를 뗀 결과다.")
    private boolean isRead;
    @Schema(description = "생성 시각. \"4분 전\" 같은 표기를 만들 수 있게 원본을 그대로 준다.")
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
