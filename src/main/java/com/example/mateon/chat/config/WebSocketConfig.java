package com.example.mateon.chat.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // FE 는 이 엔드포인트로 STOMP 핸드셰이크. CORS 는 기존 정책과 동일하게 로컬 origin 허용.
        // 같은 경로에 (1) 네이티브 WebSocket 과 (2) SockJS 를 모두 등록하여
        // FE 가 @stomp/stompjs 의 native WebSocket / SockJS 어느 쪽이든 접속할 수 있게 한다.
        registry.addEndpoint("/ws-stomp")
                .setAllowedOrigins("http://localhost:3000", "http://localhost:5173"); // 네이티브 ws://.../ws-stomp
        registry.addEndpoint("/ws-stomp")
                .setAllowedOrigins("http://localhost:3000", "http://localhost:5173")
                .withSockJS(); // SockJS fallback: /ws-stomp/**
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 인메모리 SimpleBroker. /topic 구독으로 방 브로드캐스트 수신.
        registry.enableSimpleBroker("/topic");
        // @MessageMapping 핸들러로 라우팅되는 클라이언트 발행 prefix.
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // CONNECT 프레임 JWT 인증
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
