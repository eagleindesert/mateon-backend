package com.example.mateon.chat.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

// STOMP /app/chat.send 로 들어오는 페이로드
@Getter
@NoArgsConstructor
public class ChatMessageRequest {
    private Long roomId;
    private String content;
}
