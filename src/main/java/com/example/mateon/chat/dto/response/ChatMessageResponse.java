package com.example.mateon.chat.dto.response;

import com.example.mateon.chat.domain.ChatMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;

// 단일 메시지 응답 (STOMP 브로드캐스트 + 이력 조회 공용)
@Schema(description = "메시지 한 건. 이력 조회와 STOMP 실시간 수신이 같은 형태를 쓴다.")
@Getter
public class ChatMessageResponse {
    @Schema(description = "메시지 id. 이력 페이징의 before 와 읽음 처리의 lastReadMessageId 에 이 값을 넣는다.")
    private final Long messageId;
    private final Long roomId;
    private final Long senderId;
    private final String senderName;
    private final String content;
    private final LocalDateTime createdAt;

    public ChatMessageResponse(ChatMessage message) {
        this.messageId = message.getId();
        this.roomId = message.getRoom().getId();
        this.senderId = message.getSender().getId();
        this.senderName = message.getSender().getName();
        this.content = message.getContent();
        this.createdAt = message.getCreatedAt();
    }
}
