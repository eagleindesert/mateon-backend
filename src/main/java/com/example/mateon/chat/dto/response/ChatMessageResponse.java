package com.example.mateon.chat.dto.response;

import com.example.mateon.chat.domain.ChatMessage;
import lombok.Getter;

import java.time.LocalDateTime;

// 단일 메시지 응답 (STOMP 브로드캐스트 + 이력 조회 공용)
@Getter
public class ChatMessageResponse {
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
