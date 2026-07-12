package com.example.mateon.chat.controller;

import com.example.mateon.chat.dto.request.ChatMessageRequest;
import com.example.mateon.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * WebSocket(STOMP) 채팅 메시지 수신 컨트롤러.
 * FE 는 {@code /app/chat.send} 로 발행하고, 방 구독자는 {@code /topic/room.{roomId}} 로 수신한다.
 * 브로드캐스트/알림/저장은 {@link ChatService#saveAndBroadcast} 가 처리한다.
 */
@Controller
@RequiredArgsConstructor
public class ChatStompController {

    private final ChatService chatService;

    @MessageMapping("chat.send")
    public void sendMessage(ChatMessageRequest request, Principal principal) {
        // principal 은 StompAuthChannelInterceptor 가 CONNECT 시 세팅한 인증 정보. name == userId.
        Long senderId = Long.valueOf(principal.getName());
        chatService.saveAndBroadcast(senderId, request.getRoomId(), request.getContent());
    }
}
